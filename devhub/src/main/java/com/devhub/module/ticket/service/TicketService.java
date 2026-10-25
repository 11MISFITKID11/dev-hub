package com.devhub.module.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.devhub.common.NotificationService;
import com.devhub.module.ai.common.BusinessException;
import com.devhub.module.ticket.entity.SupportTicket;
import com.devhub.module.ticket.entity.SupportTicketReply;
import com.devhub.module.ticket.mapper.SupportTicketMapper;
import com.devhub.module.ticket.mapper.SupportTicketReplyMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 工单服务：AI 客服 + 人工工单双轨闭环的人工侧。
 * <p>写路径 @Transactional；状态流转全部 CAS 条件更新（仅当状态仍为预期值时更新），
 * 并发流转（两个坐席同时受理/重复结单）只会有一个成功，失败方收到 409。
 * <p>闭环五环：拒答/点踩转单 → AI 分类定级 → 人工处理（AI 建议）→ AI 质检 → 满意度评价。
 */
@Slf4j
@Service
public class TicketService {

    private final SupportTicketMapper ticketMapper;
    private final SupportTicketReplyMapper replyMapper;
    private final TicketAiService ticketAi;
    private final NotificationService notify;
    private final Executor qcExecutor;

    public TicketService(SupportTicketMapper ticketMapper, SupportTicketReplyMapper replyMapper,
                         TicketAiService ticketAi, NotificationService notify,
                         @Qualifier("ticketQcExecutor") Executor qcExecutor) {
        this.ticketMapper = ticketMapper;
        this.replyMapper = replyMapper;
        this.ticketAi = ticketAi;
        this.notify = notify;
        this.qcExecutor = qcExecutor;
    }

    /** 建单：落库 → 生成工单号 → AI 分类定级（失败降级 OTHER/MEDIUM，不阻断） */
    @Transactional(rollbackFor = Exception.class)
    public SupportTicket create(String username, String title, String content,
                                String source, String conversationId, String messageId) {
        if (title == null || title.isBlank() || content == null || content.isBlank()) {
            throw new BusinessException("标题与内容不能为空");
        }
        SupportTicket t = new SupportTicket();
        t.setUsername(username);
        t.setTitle(title.strip());
        t.setContent(content.strip());
        t.setSource(source == null || source.isBlank() ? "manual" : source);
        t.setConversationId(conversationId);
        t.setMessageId(messageId);
        t.setStatus("PENDING");
        t.setAiClassified(0);
        ticketMapper.insert(t);
        t.setTicketNo("TK" + String.format("%06d", t.getId()));
        TicketAiService.ClassifyResult cr = ticketAi.classify(t.getTitle(), t.getContent());
        if (cr != null) {
            t.setCategory(cr.category());
            t.setPriority(cr.priority());
            t.setAiClassified(1);
        } else {
            t.setCategory("OTHER");
            t.setPriority("MEDIUM");
        }
        ticketMapper.updateById(t);
        return t;
    }

    /** 坐席视角分页（全部工单，可过滤） */
    public Page<SupportTicket> page(long page, long size, String status, String category,
                                    String priority, String keyword) {
        return ticketMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<SupportTicket>()
                .eq(notBlank(status), SupportTicket::getStatus, status)
                .eq(notBlank(category), SupportTicket::getCategory, category)
                .eq(notBlank(priority), SupportTicket::getPriority, priority)
                .like(notBlank(keyword), SupportTicket::getTitle, keyword)
                .orderByDesc(SupportTicket::getCreateTime));
    }

    /** 客户视角：只看自己的 */
    public Page<SupportTicket> mine(long page, long size, String username) {
        return ticketMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<SupportTicket>()
                .eq(SupportTicket::getUsername, username)
                .orderByDesc(SupportTicket::getCreateTime));
    }

    /** 详情（含往来回复）：发起人或坐席（管理员）可看 */
    public Map<String, Object> detail(Long id, String username, boolean admin) {
        SupportTicket t = ticketMapper.selectById(id);
        if (t == null) throw new BusinessException(404, "工单不存在");
        if (!admin && !t.getUsername().equals(username)) throw new BusinessException(403, "无权查看该工单");
        List<SupportTicketReply> replies = replyMapper.selectList(
                new LambdaQueryWrapper<SupportTicketReply>()
                        .eq(SupportTicketReply::getTicketId, id)
                        .orderByAsc(SupportTicketReply::getCreateTime));
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("ticket", t);
        vo.put("replies", replies);
        return vo;
    }

    /** 受理：CAS PENDING→PROCESSING，并发受理只有一个成功 */
    @Transactional(rollbackFor = Exception.class)
    public SupportTicket take(Long id, String handler) {
        int rows = ticketMapper.update(null, new LambdaUpdateWrapper<SupportTicket>()
                .eq(SupportTicket::getId, id)
                .eq(SupportTicket::getStatus, "PENDING")
                .set(SupportTicket::getStatus, "PROCESSING")
                .set(SupportTicket::getHandler, handler));
        if (rows == 0) throw new BusinessException(409, "工单已被受理或状态已变化，请刷新");
        return ticketMapper.selectById(id);
    }

    /** 回复：客户补充或坐席沟通（CLOSED 后不可再回复） */
    @Transactional(rollbackFor = Exception.class)
    public void reply(Long id, String author, String role, String content) {
        SupportTicket t = ticketMapper.selectById(id);
        if (t == null) throw new BusinessException(404, "工单不存在");
        if ("CLOSED".equals(t.getStatus())) throw new BusinessException("工单已关闭，无法回复");
        insertReply(id, author, role, content);
        if ("customer".equals(role) && t.getHandler() != null) {
            notify.push(t.getHandler(), "ticket", "客户补充了工单信息", t.getTitle(), "/#/tickets");
        } else if ("agent".equals(role)) {
            notify.push(t.getUsername(), "ticket", "工单有新回复", t.getTitle(), "/#/ticket/mine");
        }
    }

    private void insertReply(Long id, String author, String role, String content) {
        if (content == null || content.isBlank()) throw new BusinessException("回复内容不能为空");
        SupportTicketReply r = new SupportTicketReply();
        r.setTicketId(id);
        r.setAuthor(author);
        r.setRole(role);
        r.setContent(content.strip());
        replyMapper.insert(r);
    }

    /** 结单：CAS PROCESSING→RESOLVED，结单说明落为坐席回复，随后异步 AI 质检 */
    @Transactional(rollbackFor = Exception.class)
    public SupportTicket resolve(Long id, String handler, String resolution) {
        SupportTicket t = ticketMapper.selectById(id);
        if (t == null) throw new BusinessException(404, "工单不存在");
        int rows = ticketMapper.update(null, new LambdaUpdateWrapper<SupportTicket>()
                .eq(SupportTicket::getId, id)
                .eq(SupportTicket::getStatus, "PROCESSING")
                .set(SupportTicket::getStatus, "RESOLVED")
                .set(SupportTicket::getHandler, handler)
                .set(SupportTicket::getQcScore, null));
        if (rows == 0) throw new BusinessException(409, "仅处理中的工单可结单，请刷新");
        // 结单说明静默落为坐席回复（避免与结单通知重复打扰发起人）
        insertReply(id, handler, "agent", resolution == null || resolution.isBlank() ? "已处理完成" : resolution);
        notify.push(t.getUsername(), "ticket", "你的工单已解决，欢迎评价", t.getTitle(), "/#/ticket/mine");
        // 结单即质检：注册到事务提交后触发（提交前异步线程读到的是旧状态，会静默早退）
        Long tid = id;
        Runnable qcTask = () -> CompletableFuture.runAsync(() -> runQc(tid), qcExecutor);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { qcTask.run(); }
            });
        } else {
            qcTask.run();
        }
        return ticketMapper.selectById(id);
    }

    /** 关闭：CAS RESOLVED→CLOSED（评价后归档） */
    public void close(Long id) {
        int rows = ticketMapper.update(null, new LambdaUpdateWrapper<SupportTicket>()
                .eq(SupportTicket::getId, id)
                .eq(SupportTicket::getStatus, "RESOLVED")
                .set(SupportTicket::getStatus, "CLOSED"));
        if (rows == 0) throw new BusinessException(409, "仅已解决的工单可关闭");
    }

    /** 满意度：仅发起人、仅已解决/已关闭、仅一次 */
    @Transactional(rollbackFor = Exception.class)
    public void rate(Long id, String username, int stars, String comment) {
        SupportTicket t = ticketMapper.selectById(id);
        if (t == null) throw new BusinessException(404, "工单不存在");
        if (!t.getUsername().equals(username)) throw new BusinessException(403, "仅工单发起人可评价");
        if (!"RESOLVED".equals(t.getStatus()) && !"CLOSED".equals(t.getStatus()))
            throw new BusinessException("工单解决后才能评价");
        if (t.getSatisfaction() != null) throw new BusinessException(409, "该工单已评价过");
        if (stars < 1 || stars > 5) throw new BusinessException("评分需在 1-5 之间");
        ticketMapper.update(null, new LambdaUpdateWrapper<SupportTicket>()
                .eq(SupportTicket::getId, id)
                .isNull(SupportTicket::getSatisfaction)
                .set(SupportTicket::getSatisfaction, stars)
                .set(SupportTicket::getSatisfactionComment, comment));
    }

    /** AI 回复建议（坐席用）：检索知识库起草，人工确认后发送 */
    public String suggest(Long id) {
        SupportTicket t = ticketMapper.selectById(id);
        if (t == null) throw new BusinessException(404, "工单不存在");
        return ticketAi.suggestReply(t);
    }

    /** AI 质检（幂等可重试）：汇总坐席回复 → 评分落库 */
    public void runQc(Long id) {
        try {
            SupportTicket t = ticketMapper.selectById(id);
            if (t == null || !"RESOLVED".equals(t.getStatus()) && !"CLOSED".equals(t.getStatus())) return;
            List<SupportTicketReply> agentReplies = replyMapper.selectList(
                    new LambdaQueryWrapper<SupportTicketReply>()
                            .eq(SupportTicketReply::getTicketId, id)
                            .eq(SupportTicketReply::getRole, "agent")
                            .orderByAsc(SupportTicketReply::getCreateTime));
            if (agentReplies.isEmpty()) return;
            StringBuilder sb = new StringBuilder();
            for (SupportTicketReply r : agentReplies) sb.append("- ").append(r.getContent()).append("\n");
            TicketAiService.QcResult qc = ticketAi.qc(t, sb.toString());
            if (qc == null) return; // 保留 null，前端显示"未质检"，可手动重检
            ticketMapper.update(null, new LambdaUpdateWrapper<SupportTicket>()
                    .eq(SupportTicket::getId, id)
                    .set(SupportTicket::getQcScore, qc.score())
                    .set(SupportTicket::getQcResult,
                            "{\"score\":" + qc.score() + ",\"problems\":\"" + escape(qc.problems()) + "\"}"));
        } catch (Exception e) {
            log.error("工单质检异常 ticketId={}: {}", id, e.getMessage());
        }
    }

    /** 工单质量看板：状态/分类/优先级分布 + 满意度/质检均值 + 低分工单 + 7 日趋势 */
    public Map<String, Object> stats() {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("byStatus", ticketMapper.countByStatus());
        vo.put("byCategory", ticketMapper.countByCategory());
        vo.put("byPriority", ticketMapper.countByPriority());
        vo.put("overall", ticketMapper.overall());
        vo.put("lowQc", ticketMapper.lowQcTop5());
        vo.put("trend7d", ticketMapper.trend7d());
        return vo;
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
