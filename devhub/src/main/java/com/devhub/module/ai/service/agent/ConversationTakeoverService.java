package com.devhub.module.ai.service.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.devhub.module.ai.mapper.ConversationMapper;
import com.devhub.module.ai.mapper.MessageMapper;
import com.devhub.module.ai.model.entity.Conversation;
import com.devhub.module.ai.model.entity.Message;
import com.devhub.module.ai.model.enums.MessageRole;
import com.devhub.common.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 坐席实时接管服务（AI-first, human-in-the-loop 的 handoff 层）
 * <p>
 * 会话级状态机：NULL=AI 自动应答 → PENDING（客户转人工/建单联动，AI 停止作答）
 * → ACTIVE（坐席接管，双向实时对话）→ 释放回 NULL（AI 恢复）。
 * 客户消息在接管态直达坐席（站内信+SSE 通知），坐席回复以 AGENT 角色落库，
 * 客户端轮询拉取——不改变既有 AI 会话表结构语义，仅扩展角色。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationTakeoverService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final NotificationService notificationService;

    /** 转人工联动：会话进入待接管（幂等：已是接管态不覆盖 ACTIVE） */
    public void markPending(String conversationId, Long ticketId) {
        Conversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || STATUS_ACTIVE.equals(conv.getTakeoverStatus())) {
            return;
        }
        Conversation patch = new Conversation();
        patch.setId(conversationId);
        patch.setTakeoverStatus(STATUS_PENDING);
        if (ticketId != null) {
            patch.setTakeoverTicketId(ticketId);
        }
        patch.setUpdateTime(LocalDateTime.now());
        conversationMapper.updateById(patch);
        systemMessage(conversationId, "已为您转接人工坐席，坐席接入后可继续在本对话交流（AI 助手暂停自动回复）");
        log.info("会话进入待接管: conversationId={}, ticketId={}", conversationId, ticketId);
    }

    /** 坐席接管 */
    public Conversation takeover(String conversationId, String agent) {
        Conversation patch = new Conversation();
        patch.setId(conversationId);
        patch.setTakeoverStatus(STATUS_ACTIVE);
        patch.setHandler(agent);
        patch.setUpdateTime(LocalDateTime.now());
        conversationMapper.updateById(patch);
        systemMessage(conversationId, "人工坐席 " + agent + " 已接入本会话");
        Conversation conv = conversationMapper.selectById(conversationId);
        // 通知客户（客户侧靠轮询；通知留档全站可见）
        if (conv != null) {
            notificationService.push(conv.getUserId(), "chat",
                    "坐席已接入", "坐席 " + agent + " 已接入您的会话，请继续对话", "/ai/chat");
        }
        log.info("坐席接管会话: conversationId={}, agent={}", conversationId, agent);
        return conv;
    }

    /** 坐席释放：AI 恢复自动应答（显式置空——updateById 默认忽略 null 字段不会清列） */
    public void release(String conversationId, String agent) {
        conversationMapper.update(null,
                new LambdaUpdateWrapper<Conversation>()
                        .eq(Conversation::getId, conversationId)
                        .set(Conversation::getTakeoverStatus, null)
                        .set(Conversation::getHandler, null)
                        .set(Conversation::getUpdateTime, LocalDateTime.now()));
        systemMessage(conversationId, "人工服务已结束，AI 助手已恢复自动应答");
        log.info("坐席释放会话: conversationId={}, agent={}", conversationId, agent);
    }

    /** 接管态下客户消息：落库 + 通知坐席（AI 不作答） */
    public void customerMessage(String conversationId, String content) {
        Conversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) {
            return;
        }
        Message msg = Message.builder()
                .conversationId(conversationId)
                .role(MessageRole.USER)
                .content(content)
                .build();
        messageMapper.insert(msg);
        Conversation patch = new Conversation();
        patch.setId(conversationId);
        patch.setUpdateTime(LocalDateTime.now());
        conversationMapper.updateById(patch);
        if (conv.getHandler() != null && STATUS_ACTIVE.equals(conv.getTakeoverStatus())) {
            notificationService.push(conv.getHandler(), "chat",
                    "接管会话有新客户消息",
                    content.length() > 60 ? content.substring(0, 60) + "…" : content,
                    "/tickets/live");
        }
    }

    /** 坐席回复：AGENT 角色落库 */
    public Message agentMessage(String conversationId, String agent, String content) {
        Message msg = Message.builder()
                .conversationId(conversationId)
                .role(MessageRole.AGENT)
                .model("human:" + agent)
                .content(content)
                .build();
        messageMapper.insert(msg);
        Conversation patch = new Conversation();
        patch.setId(conversationId);
        patch.setUpdateTime(LocalDateTime.now());
        conversationMapper.updateById(patch);
        return msg;
    }

    /** 坐席侧会话列表（待接管 + 接管中，按活跃时间倒序） */
    public List<Map<String, Object>> humanList() {
        List<Conversation> convs = conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .isNotNull(Conversation::getTakeoverStatus)
                        .orderByDesc(Conversation::getUpdateTime)
                        .last("LIMIT 50"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Conversation c : convs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("title", c.getTitle());
            item.put("userId", c.getUserId());
            item.put("takeoverStatus", c.getTakeoverStatus());
            item.put("handler", c.getHandler());
            item.put("takeoverTicketId", c.getTakeoverTicketId());
            item.put("updateTime", c.getUpdateTime());
            Message last = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                            .eq(Message::getConversationId, c.getId())
                            .orderByDesc(Message::getCreateTime).last("LIMIT 1"))
                    .stream().findFirst().orElse(null);
            item.put("lastRole", last == null ? "" : last.getRole().name());
            item.put("lastMessage", last == null ? "" : abbreviate(last.getContent(), 60));
            out.add(item);
        }
        return out;
    }

    public Conversation get(String conversationId) {
        return conversationMapper.selectById(conversationId);
    }

    private void systemMessage(String conversationId, String text) {
        messageMapper.insert(Message.builder()
                .conversationId(conversationId)
                .role(MessageRole.SYSTEM)
                .content(text)
                .build());
    }

    private String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
