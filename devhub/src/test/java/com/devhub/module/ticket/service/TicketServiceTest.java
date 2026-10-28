package com.devhub.module.ticket.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.devhub.common.NotificationService;
import com.devhub.module.ai.common.BusinessException;
import com.devhub.module.ticket.entity.SupportTicket;
import com.devhub.module.ticket.entity.SupportTicketReply;
import com.devhub.module.ticket.mapper.SupportTicketMapper;
import com.devhub.module.ticket.mapper.SupportTicketReplyMapper;
import com.devhub.module.ticket.mapper.TicketTagMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 工单服务单元测试：双轨闭环关键规则
 * AI 分类降级 / 状态机 CAS 并发冲突 / 评价权限与幂等 / 结单触发 AI 质检
 */
@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    @Mock
    private SupportTicketMapper ticketMapper;
    @Mock
    private SupportTicketReplyMapper replyMapper;
    @Mock
    private TicketAiService ticketAi;
    @Mock
    private NotificationService notify;
    @Mock
    private SlaService slaService;
    @Mock
    private TicketAssignService assignService;
    @Mock
    private TicketTagMapper tagMapper;

    private TicketService service;

    @BeforeAll
    static void initTableMeta() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SupportTicket.class);
        TableInfoHelper.initTableInfo(assistant, SupportTicketReply.class);
    }

    @BeforeEach
    void setUp() {
        // 质检执行器用同步直跑，便于断言"结单即质检"
        service = new TicketService(ticketMapper, replyMapper, ticketAi, notify, Runnable::run,
                slaService, assignService, tagMapper,
                org.mockito.Mockito.mock(com.devhub.module.ai.service.agent.ConversationTakeoverService.class));
    }

    private SupportTicket ticket(long id, String username, String status) {
        SupportTicket t = new SupportTicket();
        t.setId(id);
        t.setTicketNo("TK" + String.format("%06d", id));
        t.setUsername(username);
        t.setTitle("登录失败");
        t.setContent("SSO 登录后 403");
        t.setStatus(status);
        t.setVersion(0);
        return t;
    }

    @Test
    @DisplayName("建单：AI 分类失败 → 降级 OTHER/MEDIUM，不阻断；SLA 上时钟 + 自动派单")
    void create_aiClassifyFails_fallback() {
        java.util.concurrent.atomic.AtomicReference<SupportTicket> saved = new java.util.concurrent.atomic.AtomicReference<>();
        when(ticketMapper.insert(any(SupportTicket.class))).thenAnswer(inv -> {
            SupportTicket t = inv.getArgument(0, SupportTicket.class);
            t.setId(9L);
            saved.set(t);
            return 1;
        });
        when(ticketAi.classify(any(), any())).thenReturn(null);
        when(ticketMapper.updateById(any(SupportTicket.class))).thenReturn(1);
        when(ticketMapper.selectById(9L)).thenAnswer(inv -> saved.get());

        SupportTicket t = service.create("dev01", "登录失败", "SSO 登录后 403", "chat_refusal", "c1", "m1");

        assertEquals("TK000009", t.getTicketNo());
        assertEquals("OTHER", t.getCategory());
        assertEquals("MEDIUM", t.getPriority());
        assertEquals(0, t.getAiClassified());
        assertEquals("PENDING", t.getStatus());
        verify(slaService).armClock(any(), eq(false));
        verify(assignService).autoAssign(any());
    }

    @Test
    @DisplayName("建单：AI 分类成功 → 采用 AI 结果")
    void create_aiClassified() {
        java.util.concurrent.atomic.AtomicReference<SupportTicket> saved = new java.util.concurrent.atomic.AtomicReference<>();
        when(ticketMapper.insert(any(SupportTicket.class))).thenAnswer(inv -> {
            SupportTicket t = inv.getArgument(0, SupportTicket.class);
            t.setId(10L);
            saved.set(t);
            return 1;
        });
        when(ticketAi.classify(any(), any()))
                .thenReturn(new TicketAiService.ClassifyResult("ACCOUNT", "HIGH", "账号无法登录"));
        when(ticketMapper.updateById(any(SupportTicket.class))).thenReturn(1);
        when(ticketMapper.selectById(10L)).thenAnswer(inv -> saved.get());

        SupportTicket t = service.create("dev01", "登录失败", "SSO 登录后 403", null, null, null);

        assertEquals("ACCOUNT", t.getCategory());
        assertEquals("HIGH", t.getPriority());
        assertEquals(1, t.getAiClassified());
    }

    @Test
    @DisplayName("受理：CAS 更新 0 行（已被他人受理）→ 409；已分派他人 → 409 提示转派")
    void take_casConflict_throws409() {
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1, "dev01", "PENDING"));
        when(ticketMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.take(1L, "admin"));
        assertEquals(409, ex.getCode());

        SupportTicket assigned = ticket(2, "dev01", "PENDING");
        assigned.setHandler("agent02");
        when(ticketMapper.selectById(2L)).thenReturn(assigned);
        BusinessException hijack = assertThrows(BusinessException.class, () -> service.take(2L, "admin"));
        assertEquals(409, hijack.getCode());
        assertTrue(hijack.getMessage().contains("转派"));
    }

    @Test
    @DisplayName("评价：非发起人 → 403；重复评价 → 409")
    void rate_permissionAndIdempotent() {
        SupportTicket resolved = ticket(1, "dev01", "RESOLVED");
        when(ticketMapper.selectById(1L)).thenReturn(resolved);
        BusinessException forbidden = assertThrows(BusinessException.class,
                () -> service.rate(1L, "someoneelse", 5, ""));
        assertEquals(403, forbidden.getCode());

        SupportTicket rated = ticket(1, "dev01", "RESOLVED");
        rated.setSatisfaction(4);
        when(ticketMapper.selectById(1L)).thenReturn(rated);
        BusinessException dup = assertThrows(BusinessException.class,
                () -> service.rate(1L, "dev01", 5, ""));
        assertEquals(409, dup.getCode());
    }

    @Test
    @DisplayName("结单：CAS 命中 → 结单说明落回复 + 通知发起人 + 立即触发 AI 质检落分")
    void resolve_triggersQc() {
        SupportTicket processing = ticket(1, "dev01", "PROCESSING");
        SupportTicket resolved = ticket(1, "dev01", "RESOLVED");
        when(ticketMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        // resolve 内首次读单（PROCESSING）；runQc（同步执行器）再读时已是 RESOLVED
        when(ticketMapper.selectById(1L)).thenReturn(processing, resolved);
        when(replyMapper.insert(any(SupportTicketReply.class))).thenReturn(1);
        when(replyMapper.selectList(any())).thenReturn(List.of(
                new SupportTicketReply() {{ setRole("agent"); setContent("已重置权限，请重新登录"); }}));
        when(ticketAi.qc(any(), any())).thenReturn(new TicketAiService.QcResult(88, "无问题"));

        service.resolve(1L, "admin", "已重置权限，请重新登录");

        verify(replyMapper).insert(any(SupportTicketReply.class));
        verify(notify).push(eq("dev01"), eq("ticket"), any(), any(), any());
        verify(ticketAi).qc(any(), contains("已重置权限"));
        // 质检分数落库：resolve 一次 CAS + 质检一次 set = 两次 update
        verify(ticketMapper, times(2)).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("结单：CAS 0 行（重复结单/状态已变）→ 409 且不触发质检")
    void resolve_casConflict_noQc() {
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1, "dev01", "RESOLVED"));
        when(ticketMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resolve(1L, "admin", "done"));
        assertEquals(409, ex.getCode());
        verify(ticketAi, never()).qc(any(), any());
    }
}
