package com.devhub.module.ticket;

import com.devhub.module.ticket.entity.SupportTicket;
import com.devhub.module.ticket.service.SlaClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SLA 时钟纯函数语义测试：双时钟、等待客户暂停顺延、超时判定、升级链。
 */
class SlaClockTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 25, 10, 0);

    private SupportTicket ticket(LocalDateTime resolveDue, Long pauseMs, LocalDateTime pausedAt,
                                 LocalDateTime firstDue, LocalDateTime firstAt) {
        SupportTicket t = new SupportTicket();
        t.setSlaResolveDue(resolveDue);
        t.setSlaPauseMs(pauseMs);
        t.setSlaPausedAt(pausedAt);
        t.setSlaFirstDue(firstDue);
        t.setSlaFirstAt(firstAt);
        return t;
    }

    @Test
    void resolveDue顺延累计暂停毫秒() {
        SupportTicket t = ticket(T0, 30 * 60_000L, null, null, null); // 累计暂停 30 分钟
        assertEquals(T0.plusMinutes(30), SlaClock.effectiveResolveDue(t, T0));
    }

    @Test
    void 暂停中时钟冻结不判超时() {
        // 截止已过 + 暂停 2 小时：暂停期间不判超时
        SupportTicket t = ticket(T0, 0L, T0.minusHours(2), null, null);
        assertFalse(SlaClock.resolveBreached(t, T0.plusHours(1)));
    }

    @Test
    void 未暂停且过期判超时() {
        SupportTicket t = ticket(T0, 0L, null, null, null);
        assertTrue(SlaClock.resolveBreached(t, T0.plusMinutes(1)));
        assertFalse(SlaClock.resolveBreached(t, T0.minusMinutes(1)));
    }

    @Test
    void 暂停段收口累计正确() {
        SupportTicket t = ticket(T0, 10 * 60_000L, T0, null, null);
        assertEquals(10 * 60_000L + 5 * 60_000L, SlaClock.accumulatedPause(t, T0.plusMinutes(5)));
    }

    @Test
    void 首响以首响时间判定() {
        // 截止 10:30，10:20 回复 → 未超时
        SupportTicket ok = ticket(null, 0L, null, T0.plusMinutes(30), T0.plusMinutes(20));
        assertFalse(SlaClock.firstBreached(ok, T0.plusHours(3)));
        // 截止 10:30，至今未回复且已 11:00 → 超时
        SupportTicket late = ticket(null, 0L, null, T0.plusMinutes(30), null);
        assertTrue(SlaClock.firstBreached(late, T0.plusMinutes(60)));
    }

    @Test
    void 优先级升级链封顶HIGH() {
        assertEquals("MEDIUM", SlaClock.nextPriority("LOW"));
        assertEquals("HIGH", SlaClock.nextPriority("MEDIUM"));
        assertEquals("HIGH", SlaClock.nextPriority("HIGH"));
        assertEquals("HIGH", SlaClock.nextPriority(null));
    }
}
