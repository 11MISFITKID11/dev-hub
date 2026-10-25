package com.devhub.module.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.devhub.common.NotificationService;
import com.devhub.module.ticket.entity.SlaPolicy;
import com.devhub.module.ticket.entity.SupportTicket;
import com.devhub.module.ticket.entity.SupportTicketReply;
import com.devhub.module.ticket.mapper.SlaPolicyMapper;
import com.devhub.module.ticket.mapper.SupportTicketMapper;
import com.devhub.module.ticket.mapper.SupportTicketReplyMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SLA 引擎：双时钟 + 等待客户暂停 + 超时升级。
 * <p>到期事件注册进 Redis ZSET 延迟队列（score=到期毫秒），扫描器 30s 一轮 ZRANGEBYSCORE；
 * DB 字段是事实源，每 5 分钟全量重建 ZSET 兜底（ZSET 丢失/漂移可自愈，at-least-once）。
 * <p>升级动作：标记 sla_breach → 优先级提一级 → 以建单时间锚点重算双时钟 → 系统事件回复 →
 * 通知处理人（未分派则通知全体管理员）。
 */
@Slf4j
@Service
public class SlaService {

    public static final String DUE_KEY = "sla:due";
    private static final String MEMBER_FIRST = ":FIRST";
    private static final String MEMBER_RESOLVE = ":RESOLVE";

    private final SupportTicketMapper ticketMapper;
    private final SupportTicketReplyMapper replyMapper;
    private final SlaPolicyMapper policyMapper;
    private final NotificationService notify;
    private final StringRedisTemplate redis;

    /** 策略极小表且基本不变，进程内缓存 5 分钟 */
    private volatile Map<String, SlaPolicy> policyCache = Map.of();
    private volatile long policyCacheAt = 0;

    public SlaService(SupportTicketMapper ticketMapper, SupportTicketReplyMapper replyMapper,
                      SlaPolicyMapper policyMapper, NotificationService notify,
                      StringRedisTemplate redis) {
        this.ticketMapper = ticketMapper;
        this.replyMapper = replyMapper;
        this.policyMapper = policyMapper;
        this.notify = notify;
        this.redis = redis;
    }

    private Map<String, SlaPolicy> policies() {
        long now = System.currentTimeMillis();
        if (policyCache.isEmpty() || now - policyCacheAt > 300_000) {
            Map<String, SlaPolicy> m = new ConcurrentHashMap<>();
            policyMapper.selectList(null).forEach(p -> m.put(p.getPriority(), p));
            policyCache = m;
            policyCacheAt = now;
        }
        return policyCache;
    }

    /** 建单/升级后按优先级落双时钟，并注册延迟队列（同步回填实体，便于调用方直接返回） */
    public void armClock(SupportTicket t, boolean escalateReset) {
        SlaPolicy p = policies().get(t.getPriority());
        if (p == null) return;
        LocalDateTime base = escalateReset ? LocalDateTime.now() : t.getCreateTime();
        LocalDateTime firstDue = base.plusMinutes(p.getFirstResponseMinutes());
        LocalDateTime resolveDue = base.plusMinutes(p.getResolveMinutes());
        t.setSlaFirstDue(t.getSlaFirstAt() == null ? firstDue : t.getSlaFirstDue());
        t.setSlaResolveDue(resolveDue);
        ticketMapper.update(null, new LambdaUpdateWrapper<SupportTicket>()
                .eq(SupportTicket::getId, t.getId())
                .set(SupportTicket::getSlaFirstDue, t.getSlaFirstDue())
                .set(SupportTicket::getSlaResolveDue, resolveDue));
        register(t.getId(), t.getSlaFirstAt() == null ? firstDue : null, resolveDue);
    }

    /** 注册延迟队列（首响已完成的时钟不再注册；score 用生效截止，暂停重启时刷新） */
    public void register(Long id, LocalDateTime firstDue, LocalDateTime resolveDue) {
        try {
            if (firstDue != null) redis.opsForZSet().add(DUE_KEY, id + MEMBER_FIRST,
                    firstDue.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            if (resolveDue != null) redis.opsForZSet().add(DUE_KEY, id + MEMBER_RESOLVE,
                    resolveDue.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        } catch (Exception e) {
            log.warn("SLA 延迟队列注册失败 ticketId={}: {}", id, e.getMessage());
        }
    }

    public void unregister(Long id) {
        try {
            redis.opsForZSet().remove(DUE_KEY, id + MEMBER_FIRST, id + MEMBER_RESOLVE);
        } catch (Exception ignored) { }
    }

    /** 坐席回复：收口暂停段 + 记首响 */
    public void onAgentReply(SupportTicket t) {
        if (!"PENDING".equals(t.getStatus()) && !"PROCESSING".equals(t.getStatus())) return;
        LocalDateTime now = LocalDateTime.now();
        Long pauseMs = SlaClock.accumulatedPause(t, now);
        ticketMapper.update(null, new LambdaUpdateWrapper<SupportTicket>()
                .eq(SupportTicket::getId, t.getId())
                .set(SupportTicket::getSlaPausedAt, null)
                .set(SupportTicket::getSlaPauseMs, pauseMs)
                .set(t.getSlaFirstAt() == null, SupportTicket::getSlaFirstAt, now));
        // 收口后刷新解决时钟注册分数（生效截止顺延）
        SupportTicket fresh = ticketMapper.selectById(t.getId());
        if (fresh != null) {
            register(fresh.getId(), null, SlaClock.effectiveResolveDue(fresh, now));
        }
    }

    /** 客户回复：解决时钟暂停（首响时钟不暂停） */
    public void onCustomerReply(SupportTicket t) {
        if (!"PENDING".equals(t.getStatus()) && !"PROCESSING".equals(t.getStatus())) return;
        if (t.getSlaPausedAt() != null) return; // 已在暂停中
        ticketMapper.update(null, new LambdaUpdateWrapper<SupportTicket>()
                .eq(SupportTicket::getId, t.getId())
                .set(SupportTicket::getSlaPausedAt, LocalDateTime.now()));
    }

    /** 结单/关闭：时钟终结 */
    public void onTerminal(Long id) {
        unregister(id);
    }

    /** 扫描延迟队列：30s 一轮（fixedDelay 防重入堆积） */
    @Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
    public void scan() {
        Set<String> due;
        try {
            due = redis.opsForZSet().rangeByScore(DUE_KEY, 0, System.currentTimeMillis(), 0, 100);
        } catch (Exception e) {
            log.warn("SLA 扫描失败（Redis 不可用）: {}", e.getMessage());
            return;
        }
        if (due == null || due.isEmpty()) return;
        for (String member : due) processMember(member);
    }

    private void processMember(String member) {
        try {
            // 注意：kind 是去掉冒号后的段（FIRST/RESOLVE），与常量 ":FIRST" 比较需用 endsWith
            boolean isFirst = member.endsWith(MEMBER_FIRST);
            int sep = member.lastIndexOf(':');
            Long id = Long.parseLong(member.substring(0, sep));
            SupportTicket t = ticketMapper.selectById(id);
            if (t == null || t.getDeleted() == 1
                    || !"PENDING".equals(t.getStatus()) && !"PROCESSING".equals(t.getStatus())) {
                redis.opsForZSet().remove(DUE_KEY, member); // 终态残留，清理
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            boolean breached = isFirst
                    ? SlaClock.firstBreached(t, now)
                    : SlaClock.resolveBreached(t, now);
            if (!breached) {
                // 未真正超时（可能因暂停顺延）：按生效截止重排
                redis.opsForZSet().add(DUE_KEY, member, isFirst
                        ? t.getSlaFirstDue().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        : SlaClock.effectiveResolveDue(t, now).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
                return;
            }
            escalate(t, isFirst);
            redis.opsForZSet().remove(DUE_KEY, member);
        } catch (Exception e) {
            log.warn("SLA 到期处理失败 member={}: {}", member, e.getMessage());
            redis.opsForZSet().remove(DUE_KEY, member); // 坏成员不阻塞队列
        }
    }

    /** 升级：首次超时提级并重算时钟，之后仅通知（防每轮连环升级） */
    private void escalate(SupportTicket t, boolean isFirst) {
        boolean firstTime = t.getSlaBreach() == null || t.getSlaBreach() == 0;
        String clock = isFirst ? "首响" : "解决";
        String text = "SLA " + clock + "超时（" + (isFirst
                ? "首响截止 " + t.getSlaFirstDue() : "解决截止 " + SlaClock.effectiveResolveDue(t, LocalDateTime.now()))
                + "）" + (firstTime ? "，已升级优先级 " + t.getPriority() + " → " + SlaClock.nextPriority(t.getPriority()) : "");
        if (firstTime) {
            t.setPriority(SlaClock.nextPriority(t.getPriority()));
            ticketMapper.update(null, new LambdaUpdateWrapper<SupportTicket>()
                    .eq(SupportTicket::getId, t.getId())
                    .set(SupportTicket::getSlaBreach, 1)
                    .set(SupportTicket::getPriority, t.getPriority()));
            armClock(t, true); // 升级后重算双时钟并重新注册
        }
        SupportTicketReply event = new SupportTicketReply();
        event.setTicketId(t.getId());
        event.setAuthor("system");
        event.setRole("system");
        event.setContent(text);
        replyMapper.insert(event);
        String to = t.getHandler() != null ? t.getHandler() : "admin";
        notify.push(to, "sla", "SLA 超时：" + t.getTicketNo(), text, "/#/tickets");
        log.info("SLA 升级: ticket={}, clock={}, 优先级={}", t.getTicketNo(), clock, t.getPriority());
    }

    /** 全量重建延迟队列（DB 事实源兜底，自愈丢失/漂移）：5 分钟一轮原子换键 */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void resync() {
        try {
            List<SupportTicket> active = ticketMapper.selectList(new LambdaQueryWrapper<SupportTicket>()
                    .in(SupportTicket::getStatus, "PENDING", "PROCESSING")
                    .isNotNull(SupportTicket::getSlaResolveDue));
            String tmp = DUE_KEY + ":rebuild";
            redis.delete(tmp);
            LocalDateTime now = LocalDateTime.now();
            for (SupportTicket t : active) {
                if (t.getSlaFirstAt() == null && t.getSlaFirstDue() != null
                        && t.getSlaFirstDue().isAfter(now)) {
                    redis.opsForZSet().add(tmp, t.getId() + MEMBER_FIRST,
                            t.getSlaFirstDue().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
                }
                LocalDateTime resolveDue = SlaClock.effectiveResolveDue(t, now);
                if (resolveDue != null && resolveDue.isAfter(now)) {
                    redis.opsForZSet().add(tmp, t.getId() + MEMBER_RESOLVE,
                            resolveDue.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
                }
            }
            redis.rename(tmp, DUE_KEY);
            log.info("SLA 延迟队列已重建: {} 张进行中工单", active.size());
        } catch (Exception e) {
            log.warn("SLA 队列重建失败: {}", e.getMessage());
        }
    }

    /** 展示用：解决时钟剩余毫秒（负数=已超时；暂停中返回暂停标记） */
    public Map<String, Object> clockView(SupportTicket t) {
        LocalDateTime now = LocalDateTime.now();
        boolean paused = t.getSlaPausedAt() != null;
        LocalDateTime due = SlaClock.effectiveResolveDue(t, now);
        Long remainMs = due == null ? null : Duration.between(now, due).toMillis();
        return Map.of(
                "paused", paused,
                "breached", t.getSlaBreach() != null && t.getSlaBreach() == 1,
                "firstDone", t.getSlaFirstAt() != null,
                "remainMs", remainMs == null ? 0 : remainMs);
    }
}
