package com.devhub.common;

import com.devhub.module.system.entity.SysNotification;
import com.devhub.module.system.mapper.SysNotificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 站内通知：写失败不影响业务主流程 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final SysNotificationMapper mapper;

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
    }
}
