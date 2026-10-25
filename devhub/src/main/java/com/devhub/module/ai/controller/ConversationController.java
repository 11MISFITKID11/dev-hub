package com.devhub.module.ai.controller;

import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.mapper.ConversationMapper;
import com.devhub.module.ai.mapper.MessageMapper;
import com.devhub.module.ai.model.entity.Conversation;
import com.devhub.module.ai.model.entity.Message;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话管理控制器
 * <p>
 * 历史会话列表与会话消息回放
 */
@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    /**
     * 会话列表（最近 100 条）
     */
    @GetMapping
    public Result<List<Conversation>> list() {
        // 数据域：只看自己的会话（平移改造点：原版全局可见）
        String username = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        return Result.success(conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUserId, username)
                        .orderByDesc(Conversation::getUpdateTime)
                        .last("LIMIT 100")));
    }

    /**
     * 某个会话的完整消息记录
     */
    @GetMapping("/{conversationId}/messages")
    public Result<List<Message>> messages(@PathVariable String conversationId) {
        return Result.success(messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversationId)
                        .orderByAsc(Message::getCreateTime)));
    }

    /**
     * 删除会话（逻辑删除，级联删除消息；仅本人或管理员）
     */
    @DeleteMapping("/{conversationId}")
    public Result<Void> delete(@PathVariable String conversationId) {
        var conv = conversationMapper.selectById(conversationId);
        if (conv == null) return Result.success(null);
        String username = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        boolean admin = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().contains("ADMIN"));
        if (!admin && !username.equals(conv.getUserId())) {
            return Result.fail(403, "只能删除自己的会话");
        }
        conversationMapper.deleteById(conversationId);
        messageMapper.delete(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId));
        return Result.success(null);
    }
}
