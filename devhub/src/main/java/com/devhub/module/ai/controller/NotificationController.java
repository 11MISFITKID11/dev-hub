package com.devhub.module.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.devhub.common.NotifySseHub;
import com.devhub.module.ai.common.Result;
import com.devhub.module.system.entity.SysNotification;
import com.devhub.module.system.mapper.SysNotificationMapper;
import com.devhub.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 通知中心（方案 P1-7）：列表/未读数/已读 + SSE 实时推送。
 * EventSource 无法带 Authorization 头，走 ?token= 查询参数（SecurityConfig 放行 + 此处手动校验）
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final SysNotificationMapper mapper;
    private final NotifySseHub sseHub;
    private final JwtTokenProvider jwtTokenProvider;

    /** 实时通知流：新通知经 Redis 频道推送到此连接 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String token) {
        String username;
        try {
            username = jwtTokenProvider.parse(token).getSubject();
        } catch (Exception e) {
            throw new com.devhub.module.ai.common.BusinessException(401, "token 无效");
        }
        return sseHub.register(username);
    }

    @GetMapping
    public Result<Page<SysNotification>> list(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "15") long size) {
        return Result.success(mapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<SysNotification>()
                        .eq(SysNotification::getRecipient, currentUser())
                        .orderByDesc(SysNotification::getId)));
    }

    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.success(mapper.selectCount(new LambdaQueryWrapper<SysNotification>()
                .eq(SysNotification::getRecipient, currentUser())
                .eq(SysNotification::getIsRead, 0)));
    }

    @PostMapping("/read-all")
    public Result<String> readAll() {
        SysNotification mark = new SysNotification();
        mark.setIsRead(1);
        mapper.update(mark, new LambdaQueryWrapper<SysNotification>()
                .eq(SysNotification::getRecipient, currentUser())
                .eq(SysNotification::getIsRead, 0));
        return Result.success("ok");
    }

    @PostMapping("/{id}/read")
    public Result<String> read(@PathVariable Long id) {
        SysNotification n = new SysNotification();
        n.setId(id); n.setIsRead(1);
        mapper.updateById(n);
        return Result.success("ok");
    }

    private String currentUser() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
