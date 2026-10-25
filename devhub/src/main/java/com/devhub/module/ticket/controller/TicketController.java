package com.devhub.module.ticket.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.devhub.module.ai.common.Result;
import com.devhub.module.ticket.entity.SupportTicket;
import com.devhub.module.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 智能客服工单（/api/v1/tickets）
 * 坐席端：分页/详情/受理/回复/AI建议/结单(AI质检)/关闭/看板
 * 客户端：建单（含问答拒答、点踩转单）/我的工单/回复/满意度
 */
@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    private static String me() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private static boolean isAdmin() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().contains("ADMIN"));
    }

    /** 建单（source: manual / chat_refusal / chat_dislike） */
    @PostMapping
    public Result<SupportTicket> create(@RequestBody Map<String, String> body) {
        return Result.success(ticketService.create(me(), body.get("title"), body.get("content"),
                body.get("source"), body.get("conversationId"), body.get("messageId")));
    }

    /** 坐席视角分页 */
    @GetMapping
    public Result<Page<SupportTicket>> page(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String category,
                                            @RequestParam(required = false) String priority,
                                            @RequestParam(required = false) String keyword) {
        return Result.success(ticketService.page(page, size, status, category, priority, keyword));
    }

    /** 客户视角：我的工单 */
    @GetMapping("/mine")
    public Result<Page<SupportTicket>> mine(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size) {
        return Result.success(ticketService.mine(page, size, me()));
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.success(ticketService.stats());
    }

    /** 质检统计页：已质检工单分页（管理员） */
    @GetMapping("/qc/list")
    public Result<Page<Map<String, Object>>> qcList(@RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "10") long size,
                                                    @RequestParam(required = false) Integer minScore,
                                                    @RequestParam(required = false) String category) {
        if (!isAdmin()) return Result.fail(403, "仅管理员可访问");
        return Result.success(ticketService.qcPage(page, size, minScore, category));
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.success(ticketService.detail(id, me(), isAdmin()));
    }

    @PostMapping("/{id}/take")
    public Result<SupportTicket> take(@PathVariable Long id) {
        return Result.success(ticketService.take(id, me()));
    }

    @PostMapping("/{id}/reply")
    public Result<Void> reply(@PathVariable Long id, @RequestBody Map<String, String> body) {
        // 客户回自己的单走 customer 角色；坐席（管理员）走 agent
        boolean agent = isAdmin();
        ticketService.reply(id, me(), agent ? "agent" : "customer", body.get("content"));
        return Result.success();
    }

    /** AI 回复建议：坐席起草草稿，人工确认后作为回复发送 */
    @GetMapping("/{id}/suggest")
    public Result<String> suggest(@PathVariable Long id) {
        return Result.success(ticketService.suggest(id));
    }

    @PostMapping("/{id}/resolve")
    public Result<SupportTicket> resolve(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Result.success(ticketService.resolve(id, me(), body.get("resolution")));
    }

    /** 手动（重）质检：异步质检失败或需要复核时触发 */
    @PostMapping("/{id}/qc")
    public Result<Void> qc(@PathVariable Long id) {
        ticketService.runQc(id);
        return Result.success();
    }

    @PostMapping("/{id}/close")
    public Result<Void> close(@PathVariable Long id) {
        ticketService.close(id);
        return Result.success();
    }

    @PostMapping("/{id}/rate")
    public Result<Void> rate(@PathVariable Long id, @RequestBody Map<String, String> body) {
        ticketService.rate(id, me(), Integer.parseInt(body.getOrDefault("stars", "0")), body.get("comment"));
        return Result.success();
    }
}
