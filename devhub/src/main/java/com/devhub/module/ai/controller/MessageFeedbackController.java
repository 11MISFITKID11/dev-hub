package com.devhub.module.ai.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.mapper.MessageMapper;
import com.devhub.module.ai.model.entity.Message;
import com.devhub.module.ai.service.knowledge.KnowledgeGapService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
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

    /**
     * 管理员侧问答评价分页（会话记录评价页）：按反馈/用户/时间段过滤
     */
    @GetMapping("/page")
    public Result<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "10") long size,
                                                  @RequestParam(required = false) Integer feedback,
                                                  @RequestParam(required = false) String username,
                                                  @RequestParam(required = false) String start,
                                                  @RequestParam(required = false) String end) {
        boolean admin = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().contains("ADMIN"));
        if (!admin) return Result.fail(403, "仅管理员可访问");
        return Result.success((Page<Map<String, Object>>) messageMapper.feedbackPage(
                new Page<>(page, size), feedback, blankToNull(username), start, end == null ? null : end + " 23:59:59"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

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
