package com.devhub.module.ai.controller;

import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.mapper.MessageMapper;
import com.devhub.module.ai.model.entity.Message;
import com.devhub.module.ai.service.knowledge.KnowledgeGapService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 问答反馈接口：助手消息点赞/点踩。
 * 点踩联动知识缺口记录（取该回答对应的问题），差评问题自动进入知识生产队列
 */
@RestController
@RequestMapping("/api/v1/chat/messages")
@RequiredArgsConstructor
public class MessageFeedbackController {

    private final MessageMapper messageMapper;
    private final KnowledgeGapService knowledgeGapService;
    private final JdbcTemplate jdbc;

    @PostMapping("/{id}/feedback")
    public Result<String> feedback(@PathVariable String id, @RequestBody Map<String, Integer> body) {
        Integer value = body.get("value");
        if (value == null || Math.abs(value) != 1) {
            return Result.fail(400, "value 必须为 1（有用）或 -1（无用）");
        }
        Message message = messageMapper.selectById(id);
        if (message == null) return Result.fail(404, "消息不存在");

        Message patch = new Message();
        patch.setId(id);
        patch.setFeedback(value);
        messageMapper.updateById(patch);

        // 点踩 = 答案不满意 → 找到触发问题记为知识缺口线索（高频缺口自动触发 AI 草稿生产）
        if (value == -1 && message.getConversationId() != null) {
            try {
                String question = jdbc.queryForObject(
                        "SELECT content FROM message WHERE conversation_id = ? AND role = 'USER' " +
                        "ORDER BY create_time DESC LIMIT 1",
                        String.class, message.getConversationId());
                if (question != null) {
                    knowledgeGapService.recordGap(question, message.getConversationId());
                }
            } catch (Exception ignored) { }
        }
        return Result.success("ok");
    }
}
