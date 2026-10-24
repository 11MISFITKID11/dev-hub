package com.devhub.common;

import com.devhub.module.system.entity.SysNotification;
import com.devhub.module.system.mapper.SysNotificationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 站内通知：落库 + Redis 发布订阅实时推送（SSE 集线器消费，见 NotifySseHub）。
 * 推送/写失败均不影响业务主流程
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    public static final String CHANNEL = "devhub:notify";

    private final SysNotificationMapper mapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public void push(String recipient, String type, String title, String content, String refUrl) {
        if (recipient == null || recipient.isBlank()) return;
        try {
            SysNotification n = new SysNotification();
            n.setRecipient(recipient); n.setType(type); n.setTitle(title);
            n.setContent(content); n.setRefUrl(refUrl); n.setIsRead(0);
            mapper.insert(n);
        } catch (Exception e) {
            log.warn("通知写入失败: {}", e.getMessage());
        }
        try {
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(Map.of(
                    "recipient", recipient, "type", type == null ? "" : type,
                    "title", title == null ? "" : title,
                    "content", content == null ? "" : content,
                    "refUrl", refUrl == null ? "" : refUrl)));
        } catch (Exception e) {
            log.warn("通知发布失败: {}", e.getMessage());
        }
    }
}
