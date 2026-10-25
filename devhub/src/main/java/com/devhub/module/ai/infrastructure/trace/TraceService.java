package com.devhub.module.ai.infrastructure.trace;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 链路追踪服务
 * <p>
 * 为每个请求生成唯一 traceId，记录 Agent 执行全链路日志，
 * 便于调试、性能分析和问题排查
 */
@Slf4j
@Service
public class TraceService {

    private static final String TRACE_ID_KEY = "traceId";

    /** 存储活跃的追踪上下文（结束后异步落库 ai_trace，可跨重启/多实例查询） */
    private final Map<String, TraceContext> activeTraces = new ConcurrentHashMap<>();

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public TraceService(org.springframework.jdbc.core.JdbcTemplate jdbc,
                        com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 开始一个新的追踪
     */
    public String startTrace(String operation) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(TRACE_ID_KEY, traceId);

        TraceContext context = TraceContext.builder()
                .traceId(traceId)
                .operation(operation)
                .startTime(Instant.now())
                .spans(new ArrayList<>())
                .build();
        activeTraces.put(traceId, context);

        log.info("开始追踪: operation={}, traceId={}", operation, traceId);
        return traceId;
    }

    /**
     * 添加一个追踪节点（Span）
     */
    public void addSpan(String traceId, String spanName, Map<String, Object> attributes) {
        TraceContext context = activeTraces.get(traceId);
        if (context == null) {
            log.warn("追踪上下文不存在: traceId={}", traceId);
            return;
        }

        TraceSpan span = TraceSpan.builder()
                .name(spanName)
                .timestamp(Instant.now())
                .attributes(attributes)
                .build();
        context.getSpans().add(span);

        log.debug("[Trace:{}] Span: {} | {}", traceId, spanName, attributes);
    }

    /**
     * 结束追踪
     */
    public TraceContext endTrace(String traceId) {
        TraceContext context = activeTraces.remove(traceId);
        if (context != null) {
            context.setEndTime(Instant.now());
            context.setDurationMs(
                    context.getEndTime().toEpochMilli() - context.getStartTime().toEpochMilli()
            );
            log.info("追踪结束: traceId={}, duration={}ms, spans={}",
                    traceId, context.getDurationMs(), context.getSpans().size());
            persistAsync(context);
        }
        MDC.remove(TRACE_ID_KEY);
        return context;
    }

    /** 异步落库：失败不影响主链路（trace 是排障辅助，非业务数据） */
    private void persistAsync(TraceContext context) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                String spansJson = objectMapper.writeValueAsString(context.getSpans());
                if (spansJson.length() > 60000) spansJson = spansJson.substring(0, 60000);
                jdbc.update("INSERT INTO ai_trace (trace_id, operation, duration_ms, span_count, spans) VALUES (?,?,?,?,?)",
                        context.getTraceId(), context.getOperation(), (int) context.getDurationMs(),
                        context.getSpans().size(), spansJson);
            } catch (Exception e) {
                log.warn("trace 落库失败: {}", e.getMessage());
            }
        });
    }

    /** 查询历史 trace（Ops 页/接口用）：最近 limit 条 */
    public List<Map<String, Object>> recentTraces(int limit) {
        try {
            return jdbc.queryForList("SELECT trace_id, operation, duration_ms, span_count, create_time "
                    + "FROM ai_trace ORDER BY id DESC LIMIT " + Math.min(Math.max(limit, 1), 100));
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 获取当前追踪 ID
     */
    public String currentTraceId() {
        return MDC.get(TRACE_ID_KEY);
    }

    @Data
    @Builder
    public static class TraceContext {
        private String traceId;
        private String operation;
        private Instant startTime;
        private Instant endTime;
        private long durationMs;
        private List<TraceSpan> spans;
    }

    @Data
    @Builder
    public static class TraceSpan {
        private String name;
        private Instant timestamp;
        private Map<String, Object> attributes;
    }
}
