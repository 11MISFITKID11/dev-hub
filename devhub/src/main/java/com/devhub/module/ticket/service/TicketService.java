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
import com.devhub.module.ticket.mapper.TicketTagMapper;
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
    private final SlaService slaService;
    private final TicketAssignService assignService;
    private final TicketTagMapper tagMapper;

    public TicketService(SupportTicketMapper ticketMapper, SupportTicketReplyMapper replyMapper,
                         TicketAiService ticketAi, NotificationService notify,
                         @Qualifier("ticketQcExecutor") Executor qcExecutor,
                         SlaService slaService, TicketAssignService assignService,
                         TicketTagMapper tagMapper) {
        this.ticketMapper = ticketMapper;
        this.replyMapper = replyMapper;
        this.ticketAi = ticketAi;
        this.notify = notify;
        this.qcExecutor = qcExecutor;
        this.slaService = slaService;
        this.assignService = assignService;
        this.tagMapper = tagMapper;
    }

    /** 建单：落库 → 生成工单号 → AI 分类定级（失败降级 OTHER/MEDIUM，不阻断）→ SLA 双时钟 → 自动派单 */
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
        // 重查拿 DB 回填字段（create_time 是 SLA 时钟的锚点，实体内存里没有）
        SupportTicket fresh = ticketMapper.selectById(t.getId());
        slaService.armClock(fresh, false);
        assignService.autoAssign(fresh);
        return fresh;
    }

    /**
     * 坐席视角分页（全部工单，可过滤）。
     * 快捷视图：unassigned=未分派 / risk=解决时钟 60 分钟内到期 / breached=已 SLA 超时 / mine=我处理中
     */
    public Page<SupportTicket> page(long page, long size, String status, String category,
                                    String priority, String keyword, String view) {
        LambdaQueryWrapper<SupportTicket> w = new LambdaQueryWrapper<SupportTicket>()
                .eq(notBlank(status), SupportTicket::getStatus, status)
                .eq(notBlank(category), SupportTicket::getCategory, category)
                .eq(notBlank(priority), SupportTicket::getPriority, priority)
                .like(notBlank(keyword), SupportTicket::getTitle, keyword);
        switch (view == null ? "" : view) {
            case "unassigned" -> w.isNull(SupportTicket::getHandler)
                    .eq(SupportTicket::getStatus, "PENDING");
            case "risk" -> w.in(SupportTicket::getStatus, "PENDING", "PROCESSING")
                    .eq(SupportTicket::getSlaBreach, 0)
                    .isNull(SupportTicket::getSlaPausedAt)
                    .lt(SupportTicket::getSlaResolveDue,
                            java.time.LocalDateTime.now().plusMinutes(60));
            case "breached" -> w.in(SupportTicket::getStatus, "PENDING", "PROCESSING")
                    .eq(SupportTicket::getSlaBreach, 1);
            case "mine" -> w.eq(SupportTicket::getHandler, currentUser())
                    .in(SupportTicket::getStatus, "PENDING", "PROCESSING");
            default -> { }
        }
        // 坐席开工动线：超时置顶 → 解决时钟紧迫优先 → 新单靠前（未分派/风险视图同理复用）
        return ticketMapper.selectPage(new Page<>(page, size), w
                .orderByDesc(SupportTicket::getSlaBreach)
                .orderByAsc(SupportTicket::getSlaResolveDue)
                .orderByDesc(SupportTicket::getCreateTime));
    }

    private String currentUser() {
        return org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
    }

    /** 客户视角：只看自己的 */
    public Page<SupportTicket> mine(long page, long size, String username) {
        return ticketMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<SupportTicket>()
                .eq(SupportTicket::getUsername, username)
                .orderByDesc(SupportTicket::getCreateTime));
    }

    /** 详情（含往来回复）：发起人或坐席（管理员）可看；客户视角隐藏内部备注 */
    public Map<String, Object> detail(Long id, String username, boolean admin) {
        SupportTicket t = ticketMapper.selectById(id);
        if (t == null) throw new BusinessException(404, "工单不存在");
        if (!admin && !t.getUsername().equals(username)) throw new BusinessException(403, "无权查看该工单");
        List<SupportTicketReply> replies = replyMapper.selectList(
                new LambdaQueryWrapper<SupportTicketReply>()
                        .eq(SupportTicketReply::getTicketId, id)
                        .ne(!admin, SupportTicketReply::getRole, "internal")
                        .ne(!admin, SupportTicketReply::getRole, "system")
                        .orderByAsc(SupportTicketReply::getCreateTime));
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("ticket", t);
        vo.put("replies", replies);
        vo.put("tags", tagsOf(List.of(id)).getOrDefault(id, List.of()));
        return vo;
    }

    /** 受理：CAS PENDING→PROCESSING，并发受理只有一个成功；已分派给他人的单需转派后处理 */
    @Transactional(rollbackFor = Exception.class)
    public SupportTicket take(Long id, String handler) {
        SupportTicket t = ticketMapper.selectById(id);
        if (t == null) throw new BusinessException(404, "工单不存在");
        if (t.getHandler() != null && !t.getHandler().isBlank() && !t.getHandler().equals(handler)) {
            throw new BusinessException(409, "该工单已分派给 " + t.getHandler() + "，如需接管请先转派");
        }
        int rows = ticketMapper.update(null, new LambdaUpdateWrapper<SupportTicket>()
                .eq(SupportTicket::getId, id)
                .eq(SupportTicket::getStatus, "PENDING")
                .set(SupportTicket::getStatus, "PROCESSING")
                .set(SupportTicket::getHandler, handler));
        if (rows == 0) throw new BusinessException(409, "工单已被受理或状态已变化，请刷新");
        return ticketMapper.selectById(id);
    }

    /** 回复：customer/agent 沟通 + agent internal 内部备注（CLOSED 后不可再回复）。
     *  SLA 钩子：客户回复暂停解决时钟；坐席回复收口暂停并记首响。 */
    @Transactional(rollbackFor = Exception.class)
    public void reply(Long id, String author, String role, String content) {
        SupportTicket t = ticketMapper.selectById(id);
        if (t == null) throw new BusinessException(404, "工单不存在");
        if ("CLOSED".equals(t.getStatus())) throw new BusinessException("工单已关闭，无法回复");
        if (!"customer".equals(role) && !"agent".equals(role) && !"internal".equals(role))
            throw new BusinessException("非法回复角色");
        insertReply(id, author, role, content);
        if ("customer".equals(role)) {
            slaService.onCustomerReply(t);
            if (t.getHandler() != null) {
                notify.push(t.getHandler(), "ticket", "客户补充了工单信息", t.getTitle(), "/#/tickets");
            }
        } else if ("agent".equals(role)) {
            slaService.onAgentReply(t);
            notify.push(t.getUsername(), "ticket", "工单有新回复", t.getTitle(), "/#/ticket/mine");
        }
        // internal 备注：坐席内部可见，不通知客户、不动 SLA 时钟
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
        slaService.onTerminal(id);
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
        slaService.onTerminal(id);
    }

    // ==================== 标签 ====================

    public List<com.devhub.module.ticket.entity.TicketTag> tagList() {
        return tagMapper.selectList(new LambdaQueryWrapper<com.devhub.module.ticket.entity.TicketTag>()
                .orderByAsc(com.devhub.module.ticket.entity.TicketTag::getId));
    }

    public com.devhub.module.ticket.entity.TicketTag tagCreate(String name, String color) {
        if (name == null || name.isBlank()) throw new BusinessException("标签名不能为空");
        Long cnt = tagMapper.selectCount(new LambdaQueryWrapper<com.devhub.module.ticket.entity.TicketTag>()
                .eq(com.devhub.module.ticket.entity.TicketTag::getName, name.strip()));
        if (cnt != null && cnt > 0) throw new BusinessException(409, "标签已存在");
        com.devhub.module.ticket.entity.TicketTag tag = new com.devhub.module.ticket.entity.TicketTag();
        tag.setName(name.strip());
        tag.setColor(color == null || color.isBlank() ? "#0071e3" : color);
        tagMapper.insert(tag);
        return tag;
    }

    public void tagDelete(Long id) {
        tagMapper.deleteById(id);
        tagMapper.detachByTag(id);
    }

    public void tagAttach(Long ticketId, Long tagId) {
        SupportTicket t = ticketMapper.selectById(ticketId);
        if (t == null) throw new BusinessException(404, "工单不存在");
        tagMapper.attach(ticketId, tagId);
    }

    public void tagDetach(Long ticketId, Long tagId) {
        tagMapper.detach(ticketId, tagId);
    }

    /** 批量取工单标签（列表页展示） */
    public Map<Long, List<Map<String, Object>>> tagsOf(List<Long> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) return Map.of();
        String ids = String.join(",", ticketIds.stream().map(String::valueOf).toList());
        List<Map<String, Object>> rows = tagMapper.tagsOfTickets(ids);
        Map<Long, List<Map<String, Object>>> m = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Long tid = ((Number) r.get("ticketId")).longValue();
            m.computeIfAbsent(tid, k -> new java.util.ArrayList<>()).add(r);
        }
        return m;
    }

    /** 列表页：工单 + 标签合并视图 */
    public Map<String, Object> pageWithTags(long page, long size, String status, String category,
                                            String priority, String keyword, String view) {
        Page<SupportTicket> p = page(page, size, status, category, priority, keyword, view);
        Map<Long, List<Map<String, Object>>> tags = tagsOf(
                p.getRecords().stream().map(SupportTicket::getId).toList());
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("records", p.getRecords());
        vo.put("tags", tags);
        vo.put("total", p.getTotal());
        vo.put("current", p.getCurrent());
        vo.put("size", p.getSize());
        return vo;
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

    /** 工单质量看板：状态/分类/优先级分布 + 满意度/质检均值 + 低分工单 + 7 日趋势 + 满意度分布/质检覆盖 */
    public Map<String, Object> stats() {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("byStatus", ticketMapper.countByStatus());
        vo.put("byCategory", ticketMapper.countByCategory());
        vo.put("byPriority", ticketMapper.countByPriority());
        vo.put("overall", ticketMapper.overall());
        vo.put("lowQc", ticketMapper.lowQcTop5());
        vo.put("trend7d", ticketMapper.trend7d());
        vo.put("satisfactionDist", ticketMapper.countBySatisfaction());
        vo.put("qcDone", ticketMapper.qcDoneCount());
        vo.put("views", ticketMapper.viewStats());
        return vo;
    }

    /** 质检统计页：已质检工单分页（低分优先），支持分数区间过滤 */
    public Page<Map<String, Object>> qcPage(long page, long size, Integer minScore, String category) {
        LambdaQueryWrapper<SupportTicket> w = new LambdaQueryWrapper<SupportTicket>()
                .isNotNull(SupportTicket::getQcScore)
                .ge(minScore != null, SupportTicket::getQcScore, minScore)
                .eq(notBlank(category), SupportTicket::getCategory, category)
                .orderByAsc(SupportTicket::getQcScore);
        Page<SupportTicket> r = ticketMapper.selectPage(new Page<>(page, size), w);
        List<Map<String, Object>> rows = r.getRecords().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("ticketNo", t.getTicketNo());
            m.put("username", t.getUsername());
            m.put("title", t.getTitle());
            m.put("category", t.getCategory());
            m.put("priority", t.getPriority());
            m.put("status", t.getStatus());
            m.put("handler", t.getHandler());
            m.put("qcScore", t.getQcScore());
            m.put("qcResult", t.getQcResult());
            m.put("satisfaction", t.getSatisfaction());
            m.put("updateTime", t.getUpdateTime());
            return m;
        }).toList();
        Page<Map<String, Object>> out = new Page<>(r.getCurrent(), r.getSize(), r.getTotal());
        out.setRecords(rows);
        return out;
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
