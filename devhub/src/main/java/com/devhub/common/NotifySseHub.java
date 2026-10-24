package com.devhub.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 通知 SSE 集线器：按用户名维护在线连接，订阅 Redis devhub:notify 频道路由实时推送。
 * 单实例内存注册表（与当前部署形态一致）；多实例时同一频道消息各实例都会收到，
 * 本地无该用户连接则自然忽略，无需额外协调
 */
@Slf4j
@Component
public class NotifySseHub implements MessageListener {

    private final Map<String, List<SseEmitter>> byUser = new ConcurrentHashMap<>();

    public SseEmitter register(String username) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        byUser.computeIfAbsent(username, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(username, emitter));
        emitter.onTimeout(() -> remove(username, emitter));
        emitter.onError(e -> remove(username, emitter));
        try {
            emitter.send(SseEmitter.event().name("hello").data("connected"));
        } catch (IOException ignored) { }
        return emitter;
    }

    /** Redis 频道消息：按 recipient 路由到该用户的全部连接 */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            String recipient = node.path("recipient").asText("");
            List<SseEmitter> emitters = byUser.get(recipient);
            if (emitters == null || emitters.isEmpty()) return;
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name("notify").data(json));
                } catch (Exception e) {
                    remove(recipient, emitter);
                }
            }
            log.debug("通知实时推送: recipient={}, connections={}", recipient, emitters.size());
        } catch (Exception e) {
            log.warn("通知推送解析失败: {}", e.getMessage());
        }
    }

    /** 心跳：30 秒一次防代理断连，顺带清理死连接 */
    @Scheduled(fixedDelay = 30_000)
    public void heartbeat() {
        byUser.forEach((user, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    remove(user, emitter);
                }
            }
        });
    }

    private void remove(String username, SseEmitter emitter) {
        List<SseEmitter> emitters = byUser.get(username);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) byUser.remove(username);
        }
    }
}
