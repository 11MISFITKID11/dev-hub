package com.devhub.module.ticket.service;

import com.devhub.module.ticket.entity.SupportTicket;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * SLA 时钟纯函数（无状态，便于单测）。
 * <p>双时钟语义：
 * <ul>
 *   <li>首响时钟：建单起算到坐席首次回复，不暂停；</li>
 *   <li>解决时钟：建单起算到结单；<b>等待客户回复期间暂停</b>（Zammad 语义）——
 *       暂停累计入 slaPauseMs，生效截止 = slaResolveDue + 累计暂停。</li>
 * </ul>
 */
public final class SlaClock {

    private SlaClock() {}

    /** 解决时钟生效截止 = 基准截止 + 已累计暂停 + 当前未收口的暂停段 */
    public static LocalDateTime effectiveResolveDue(SupportTicket t, LocalDateTime now) {
        if (t == null || t.getSlaResolveDue() == null) return null;
        LocalDateTime due = t.getSlaResolveDue();
        if (t.getSlaPauseMs() != null && t.getSlaPauseMs() > 0) {
            due = due.plusNanos(t.getSlaPauseMs() * 1_000_000L);
        }
        if (t.getSlaPausedAt() != null && now != null) {
            due = due.plus(Duration.between(t.getSlaPausedAt(), now));
        }
        return due;
    }

    /** 首响是否超时（已首响则以首响时间判定，未首响以当前时间判定） */
    public static boolean firstBreached(SupportTicket t, LocalDateTime now) {
        if (t == null || t.getSlaFirstDue() == null) return false;
        LocalDateTime at = t.getSlaFirstAt() != null ? t.getSlaFirstAt() : now;
        return at.isAfter(t.getSlaFirstDue());
    }

    /** 解决是否超时（暂停期间时钟冻结：暂停中不判定） */
    public static boolean resolveBreached(SupportTicket t, LocalDateTime now) {
        if (t == null) return false;
        if (t.getSlaPausedAt() != null) return false;
        LocalDateTime due = effectiveResolveDue(t, now);
        return due != null && now.isAfter(due);
    }

    /** 超时升级：优先级提一级（封顶 HIGH） */
    public static String nextPriority(String priority) {
        return switch (priority == null ? "" : priority) {
            case "LOW" -> "MEDIUM";
            case "MEDIUM" -> "HIGH";
            default -> "HIGH";
        };
    }

    /** 暂停片段收口：客户等待结束（坐席回复）时累计暂停毫秒 */
    public static long accumulatedPause(SupportTicket t, LocalDateTime now) {
        if (t.getSlaPausedAt() == null) return t.getSlaPauseMs() == null ? 0 : t.getSlaPauseMs();
        long base = t.getSlaPauseMs() == null ? 0 : t.getSlaPauseMs();
        return base + Duration.between(t.getSlaPausedAt(), now).toMillis();
    }
}
