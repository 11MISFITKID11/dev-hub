package com.devhub.module.ai.service.tool.builtin;

import com.devhub.module.ai.service.tool.BaseTool;
import com.devhub.module.ticket.entity.SupportTicket;
import com.devhub.module.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工单创建工具（Agentic 客服）
 * <p>当客户诉求无法当场解决（设备故障报修、投诉、退款申请等）时，由 AI 直接创建工单，
 * 走完整人工管道：AI 分类定级 → SLA 双时钟 → 自动派单。返回工单号供客户留档。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketCreateTool extends BaseTool {

    private final TicketService ticketService;

    @Override
    public String getName() { return "create_ticket"; }

    @Override
    public String getDescription() {
        return "为客户创建售后工单（设备报修、故障申报、退款申请等无法当场解决的问题）。"
                + "调用后返回工单号，客户可凭工单号在「我的工单」跟进。";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "title": { "type": "string", "description": "工单标题（一句话概括问题）" },
                    "content": { "type": "string", "description": "问题详情（现象、设备、影响范围、已尝试的操作）" }
                  },
                  "required": ["title", "content"]
                }
                """;
    }

    @Override
    protected void validate(Map<String, Object> parameters) {
        if (String.valueOf(parameters.getOrDefault("title", "")).isBlank()
                || String.valueOf(parameters.getOrDefault("content", "")).isBlank()) {
            throw new IllegalArgumentException("title 与 content 不能为空");
        }
    }

    @Override
    protected String doExecute(Map<String, Object> parameters) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        SupportTicket t = ticketService.create(username,
                String.valueOf(parameters.get("title")).strip(),
                String.valueOf(parameters.get("content")).strip(),
                "ai_agent", null, null);
        return "工单已创建：" + t.getTicketNo() + "（分类 " + t.getCategory() + " / 优先级 " + t.getPriority()
                + (t.getHandler() != null ? "，已派发给坐席 " + t.getHandler() : "") + "）。请向客户确认工单号。";
    }
}
