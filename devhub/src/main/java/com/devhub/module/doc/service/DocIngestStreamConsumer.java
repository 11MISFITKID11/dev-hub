package com.devhub.module.doc.service;

import com.devhub.module.ai.mapper.DocumentMapper;
import com.devhub.module.ai.model.entity.Document;
import com.devhub.module.doc.config.DocIngestStreamConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档入库 Stream 消费者
 * <p>
 * 消费模式：消费者组 + 手动 ACK（至少一次投递）。
 * 处理成功才 ACK；失败留在 PENDING LIST，由 {@link #recoverPendingMessages()}
 * 认领空闲超阈值的消息重试；投递次数达上限进入死信处理
 * （清理外壳行与临时文件后 ACK，错误日志留档提示人工重传）。
 * 幂等由 {@link AsyncIngestService#processIngest} 以外壳行状态保证。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocIngestStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    /** 判定为"消费中断/崩溃残留"的最小空闲时间（大于单次 ETL 预期耗时，避免与正常消费重叠） */
    private static final Duration MIN_IDLE = Duration.ofMinutes(2);
    /** 最大投递次数：达到即死信，防毒丸消息无限重试 */
    private static final long MAX_DELIVERIES = 3;

    private final AsyncIngestService asyncIngestService;
    private final StringRedisTemplate redisTemplate;
    private final DocumentMapper documentMapper;

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        handle(message.getValue(), message.getId());
    }

    /** 统一处理入口：成功 ACK + 清临时文件；失败留 PENDING */
    private void handle(Map<String, String> v, RecordId id) {
        String documentId = v.get("documentId");
        try {
            asyncIngestService.processIngest(toEvent(v));
            ack(id);
            deleteTempQuietly(v.get("tempPath"));
        } catch (Exception e) {
            // 不 ACK：留在 PENDING，等待恢复任务认领重试或死信
            log.error("入库消费失败（留 PENDING 等待重试）: id={}, file={}, cause={}",
                    documentId, v.get("fileName"), e.getMessage());
        }
    }

    /**
     * PENDING 恢复：每分钟扫描，认领空闲超 2 分钟的消息重试；
     * 投递次数达上限的死信处理。覆盖两类场景：
     * 消费中途崩溃/重启（消息无人 ACK）、持续失败反复重投
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void recoverPendingMessages() {
        PendingMessages pending;
        try {
            pending = redisTemplate.opsForStream().pending(
                    DocIngestStreamConfig.STREAM_KEY, DocIngestStreamConfig.GROUP,
                    Range.unbounded(), 50);
        } catch (Exception e) {
            log.warn("PENDING 扫描失败（Redis 不可用？）: {}", e.getMessage());
            return;
        }
        if (pending == null || pending.isEmpty()) {
            return;
        }

        List<RecordId> retryIds = new ArrayList<>();
        List<RecordId> deadIds = new ArrayList<>();
        for (PendingMessage pm : pending) {
            if (pm.getElapsedTimeSinceLastDelivery().compareTo(MIN_IDLE) < 0) {
                continue;
            }
            if (pm.getTotalDeliveryCount() >= MAX_DELIVERIES) {
                deadIds.add(pm.getId());
            } else {
                retryIds.add(pm.getId());
            }
        }

        if (!retryIds.isEmpty()) {
            List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream().claim(
                    DocIngestStreamConfig.STREAM_KEY, DocIngestStreamConfig.GROUP,
                    DocIngestStreamConfig.CONSUMER, MIN_IDLE,
                    retryIds.toArray(RecordId[]::new));
            for (MapRecord<String, Object, Object> rec : claimed) {
                handle(toStringMap(rec.getValue()), rec.getId());
            }
            if (!claimed.isEmpty()) {
                log.info("PENDING 恢复：认领重试 {} 条入库任务", claimed.size());
            }
        }
        if (!deadIds.isEmpty()) {
            deadLetter(deadIds);
        }
    }

    /**
     * 死信终态处理：外壳行若仍是 processing 则删除（不留幽灵行），
     * 删除临时文件后 ACK，错误日志留档供人工重传
     */
    private void deadLetter(List<RecordId> ids) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().claim(
                DocIngestStreamConfig.STREAM_KEY, DocIngestStreamConfig.GROUP,
                DocIngestStreamConfig.CONSUMER, Duration.ZERO,
                ids.toArray(RecordId[]::new));
        for (MapRecord<String, Object, Object> rec : records) {
            Map<String, String> v = toStringMap(rec.getValue());
            String documentId = v.get("documentId");
            try {
                Document doc = documentId == null ? null : documentMapper.selectById(documentId);
                if (doc != null && "completed".equals(doc.getStatus())) {
                    continue;   // 实际已成功（ACK 丢失），下方统一 ACK
                }
                if (doc != null) {
                    documentMapper.deleteById(documentId);
                }
                log.error("入库死信（重试 {} 次仍失败，外壳行与临时文件已清理，请人工重传）: id={}, file={}",
                        MAX_DELIVERIES, documentId, v.get("fileName"));
            } finally {
                deleteTempQuietly(v.get("tempPath"));
                ack(rec.getId());
            }
        }
    }

    /** claim 返回的 hash 键值静态类型为 Object（实际是字符串），统一转回 String 视图 */
    private Map<String, String> toStringMap(Map<Object, Object> source) {
        Map<String, String> target = new java.util.HashMap<>();
        source.forEach((k, val) -> target.put(String.valueOf(k), val == null ? null : String.valueOf(val)));
        return target;
    }

    private AsyncIngestService.DocumentIngestEvent toEvent(Map<String, String> v) {
        return new AsyncIngestService.DocumentIngestEvent(
                v.get("documentId"), v.get("fileName"), v.get("contentType"),
                Path.of(v.get("tempPath")), Long.parseLong(v.get("fileSize")),
                v.get("uploader"), parseLong(v.get("categoryId")), parseLong(v.get("deptId")),
                v.getOrDefault("content", ""));
    }

    private Long parseLong(String s) {
        return (s == null || s.isBlank()) ? null : Long.parseLong(s);
    }

    private void ack(RecordId id) {
        try {
            redisTemplate.opsForStream().acknowledge(
                    DocIngestStreamConfig.STREAM_KEY, DocIngestStreamConfig.GROUP, id);
        } catch (Exception e) {
            log.warn("ACK 失败（将由 PENDING 恢复任务兜底）: id={}, cause={}", id, e.getMessage());
        }
    }

    private void deleteTempQuietly(String tempPath) {
        if (tempPath == null || tempPath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(tempPath));
        } catch (Exception ignored) { }
    }
}
