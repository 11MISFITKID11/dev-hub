package com.devhub.module.ticket;

import com.devhub.module.ai.infrastructure.vectordb.MilvusService;
import com.devhub.module.ticket.entity.SupportTicket;
import com.devhub.module.ticket.service.SlaService;
import com.devhub.module.ticket.service.TicketAiService;
import com.devhub.module.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 工单全流转集成测试（真实 MySQL + Redis，loop_test.py 的 Java 化）。
 * <p>运行方式：DEVHUB_IT=local mvn test -Dtest=TicketFlowIntegrationTest ——
 * 直连本机中间件：独立 schema devhub_it（每库全新 Flyway V1-V31 迁移）+ Redis db15 隔离，
 * 与开发环境（devhub 库 / db0）互不影响；默认（无 env）跳过，不拖慢常规单测与 CI。
 * <p>价值：CAS 状态机 / SLA 双时钟 / 自动派单 / 异步质检走真实 DB+Redis 全编排，
 * LLM 侧（Milvus/AI 分类质检）Mockito 隔离——本测试只验工程链路。
 * TODO: CI Linux 环境可切 Testcontainers（本地 Docker Desktop 新管道与 docker-java 不兼容）。
 */
@EnabledIfEnvironmentVariable(named = "DEVHUB_IT", matches = "local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "OPENAI_API_KEY=dummy", "OPENAI_BASE_URL=http://localhost:9",
        "DEEPSEEK_API_KEY=dummy", "AGENT_RAG_RERANK_PROVIDER=none"
})
class TicketFlowIntegrationTest {

    static {
        // 类加载即执行（早于 Spring 上下文）：显式按 utf8mb4_0900_ai_ci 重建独立测试库。
        // 不用 createDatabaseIfNotExist——connector 建库默认 unicode_ci 会与后续 DDL 的 0900
        // 混出两种表排序规则，V25 的 JOIN 比较直接报 Illegal mix of collations（实测踩坑）。
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:mysql://localhost:3307/?useSSL=false&allowPublicKeyRetrieval=true", "root", "root");
             java.sql.Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS devhub_it");
            s.execute("CREATE DATABASE devhub_it CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (Exception e) {
            throw new IllegalStateException("测试库 devhub_it 初始化失败（需本机 MySQL 3307 root/root）: " + e.getMessage(), e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () ->
                "jdbc:mysql://localhost:3307/devhub_it"
                        + "?useUnicode=true&characterEncoding=utf-8&useSSL=false"
                        + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        r.add("spring.datasource.username", () -> "root");
        r.add("spring.datasource.password", () -> "root");
        r.add("spring.data.redis.host", () -> "localhost");
        r.add("spring.data.redis.port", () -> "6379");
        r.add("spring.data.redis.database", () -> "15"); // 与开发环境 db0 隔离
    }

    @MockitoBean
    MilvusService milvus;
    @MockitoBean
    TicketAiService ticketAi;

    @Autowired TicketService tickets;
    @Autowired SlaService sla;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate srt;

    @BeforeEach
    void seedUsersAndStubAi() {
        jdbc.update("INSERT IGNORE INTO sys_user (username, nickname, password, dept_id) VALUES (?,?,?,?)",
                "admin", "主管", "x", 1);
        jdbc.update("INSERT IGNORE INTO sys_user (username, nickname, password, dept_id) VALUES (?,?,?,?)",
                "agent02", "坐席小王", "x", 1);
        jdbc.update("INSERT IGNORE INTO sys_user (username, nickname, password, dept_id) VALUES (?,?,?,?)",
                "dev01", "客户", "x", 2);
        jdbc.update("INSERT IGNORE INTO sys_user_role (username, role_id) "
                + "SELECT 'admin', id FROM sys_role WHERE role_key='ADMIN'");
        jdbc.update("INSERT IGNORE INTO sys_user_role (username, role_id) "
                + "SELECT 'agent02', id FROM sys_role WHERE role_key='ADMIN'");
        jdbc.update("INSERT IGNORE INTO sys_user_role (username, role_id) "
                + "SELECT 'dev01', id FROM sys_role WHERE role_key='USER'");
        when(ticketAi.classify(any(), any()))
                .thenReturn(new TicketAiService.ClassifyResult("BUG", "HIGH", "集成测试"));
        when(ticketAi.qc(any(), any()))
                .thenReturn(new TicketAiService.QcResult(88, "无问题"));
    }

    private void login(String user, boolean admin) {
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        user, null, List.of(() -> (admin ? "ROLE_ADMIN" : "ROLE_USER"))));
    }

    @Test
    @DisplayName("黄金链路：建单(分类/SLA/派单)→受理→暂停/首响→备注隔离→结单→质检→评价→统计")
    void fullTicketLifecycle() {
        login("dev01", false);
        SupportTicket t = tickets.create("dev01", "集成测试：网关掉线", "12 台 G3 全部红灯", "chat_refusal", null, null);
        assertNotNull(t.getTicketNo());
        assertEquals("BUG", t.getCategory());
        assertTrue(t.getHandler() == null || List.of("admin", "agent02").contains(t.getHandler()),
                "自动派单应命中坐席或留空");
        assertNotNull(t.getSlaFirstDue(), "SLA 双时钟应已挂");

        String handler = t.getHandler() != null ? t.getHandler() : "admin";
        login(handler, true);
        assertEquals("PROCESSING", tickets.take(t.getId(), handler).getStatus());

        // 客户补充 → 解决时钟暂停
        tickets.reply(t.getId(), "dev01", "customer", "SN 前缀 CS-G3-2207");
        assertTrue(jdbc.queryForObject("SELECT sla_paused_at IS NOT NULL FROM support_ticket WHERE id=?",
                Boolean.class, t.getId()), "客户回复应暂停解决时钟");

        // 坐席回复 → 首响 + 暂停收口
        tickets.reply(t.getId(), handler, "agent", "已定位固件缺陷，正在下发回滚补丁");
        assertTrue(jdbc.queryForObject(
                "SELECT sla_first_at IS NOT NULL AND sla_paused_at IS NULL AND sla_pause_ms > 0 "
                        + "FROM support_ticket WHERE id=?", Boolean.class, t.getId()));

        // 内部备注客户不可见
        tickets.reply(t.getId(), handler, "internal", "内部：与昨日案例同批");
        @SuppressWarnings("unchecked")
        List<com.devhub.module.ticket.entity.SupportTicketReply> replies =
                (List<com.devhub.module.ticket.entity.SupportTicketReply>) tickets
                        .detail(t.getId(), "dev01", false).get("replies");
        assertTrue(replies.stream().noneMatch(r -> "internal".equals(r.getRole())));

        // 结单 → 异步质检落分
        tickets.resolve(t.getId(), handler, "回滚补丁已下发，设备恢复");
        for (int i = 0; i < 30 && jdbc.queryForObject(
                "SELECT qc_score FROM support_ticket WHERE id=?", Integer.class, t.getId()) == null; i++) {
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        }
        assertEquals(88, jdbc.queryForObject("SELECT qc_score FROM support_ticket WHERE id=?",
                Integer.class, t.getId()));

        // 客户评价 → 关闭
        tickets.rate(t.getId(), "dev01", 5, "很快");
        tickets.close(t.getId());
        assertEquals("CLOSED", jdbc.queryForObject("SELECT status FROM support_ticket WHERE id=?",
                String.class, t.getId()));
        assertNull(srt.opsForZSet().score(SlaService.DUE_KEY, t.getId() + ":RESOLVE"),
                "终态工单应移出 SLA 延迟队列");
    }

    @Test
    @DisplayName("SLA 升级：首响超时 → 提级+事件回复；首响时钟不再重挂（无重复轰炸）")
    void slaEscalationOneshot() {
        when(ticketAi.classify(any(), any()))
                .thenReturn(new TicketAiService.ClassifyResult("USAGE", "LOW", "低优先"));
        login("dev01", false);
        SupportTicket t = tickets.create("dev01", "SLA升级测试", "LOW 单验证超时升级", "manual", null, null);
        assertEquals("LOW", t.getPriority());

        jdbc.update("UPDATE support_ticket SET sla_first_due = DATE_SUB(NOW(), INTERVAL 5 MINUTE) WHERE id=?",
                t.getId());
        srt.opsForZSet().add(SlaService.DUE_KEY, t.getId() + ":FIRST", 1);
        sla.scan();

        assertEquals(1, jdbc.queryForObject("SELECT sla_breach FROM support_ticket WHERE id=?",
                Integer.class, t.getId()));
        assertEquals("MEDIUM", jdbc.queryForObject("SELECT priority FROM support_ticket WHERE id=?",
                String.class, t.getId()), "首次超时应提级 LOW→MEDIUM");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM support_ticket_reply WHERE ticket_id=? AND role='system'",
                Integer.class, t.getId()), "升级事件恰好一条");
        assertNull(srt.opsForZSet().score(SlaService.DUE_KEY, t.getId() + ":FIRST"),
                "首响时钟超时后不应重挂（防每周期重复轰炸）");

        @SuppressWarnings("unchecked")
        List<com.devhub.module.ticket.entity.SupportTicketReply> sysReplies =
                (List<com.devhub.module.ticket.entity.SupportTicketReply>) tickets
                        .detail(t.getId(), "dev01", false).get("replies");
        assertTrue(sysReplies.stream().noneMatch(r -> "system".equals(r.getRole())), "系统事件客户不可见");
    }
}
