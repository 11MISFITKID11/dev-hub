package com.devhub.module.ai.controller;

import com.devhub.module.ai.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agentic 客服运营指标（管理员）：AI 自主解决率与工具使用分布
 * <p>解决率口径：带工具调用且工具成功完成的会话 / 总对话（7 天）。
 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentStatsController {

    private final JdbcTemplate jdbc;

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        Long chats = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT conversation_id) FROM message WHERE create_time >= DATE_SUB(NOW(), INTERVAL 7 DAY) AND role = 'user'",
                Long.class);
        if (chats == null) chats = 0L;
        // 工具成功的去重会话数（agent_action 未记 conversation_id，以 (username, 分钟级时间窗) 近似去重）
        Long solved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT username, DATE_FORMAT(create_time, '%Y-%m-%d %H:%i') AS w "
                        + "FROM agent_action WHERE success = 1 AND create_time >= DATE_SUB(NOW(), INTERVAL 7 DAY) "
                        + "GROUP BY username, w) t",
                Long.class);
        if (solved == null) solved = 0L;
        List<Map<String, Object>> byTool = jdbc.queryForList(
                "SELECT tool_name AS k, COUNT(*) AS v, SUM(success) AS ok, ROUND(AVG(elapsed_ms)) AS avgMs "
                        + "FROM agent_action WHERE create_time >= DATE_SUB(NOW(), INTERVAL 7 DAY) GROUP BY tool_name");
        List<Map<String, Object>> recent = jdbc.queryForList(
                "SELECT username, tool_name, args, success, elapsed_ms, create_time FROM agent_action "
                        + "ORDER BY id DESC LIMIT 10");
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("chats7d", chats);
        vo.put("toolSolved7d", solved);
        vo.put("resolutionRate", chats == 0 ? 0 : Math.min(100, solved * 100 / chats));
        vo.put("byTool", byTool);
        vo.put("recent", recent);
        return Result.success(vo);
    }
}
