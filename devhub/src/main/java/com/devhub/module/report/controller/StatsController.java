package com.devhub.module.report.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devhub.module.ai.common.Result;
import com.devhub.module.defect.entity.PrjDefect;
import com.devhub.module.defect.mapper.PrjDefectMapper;
import com.devhub.module.project.mapper.PrjProjectMapper;
import com.devhub.module.task.entity.PrjTask;
import com.devhub.module.task.mapper.PrjTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 工作台 + 报表：计数卡 / 任务看板分布 / 完成率 / 缺陷 7 日趋势 / AI 用量
 */
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class StatsController {

    private final PrjProjectMapper projectMapper;
    private final PrjTaskMapper taskMapper;
    private final PrjDefectMapper defectMapper;
    private final JdbcTemplate jdbc;

    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard() {
        String me = currentUsername();
        Map<String, Object> data = new LinkedHashMap<>();

        // 计数卡
        data.put("activeProjects", projectMapper.selectCount(
                new LambdaQueryWrapper<com.devhub.module.project.entity.PrjProject>()
                        .eq(com.devhub.module.project.entity.PrjProject::getStatus, 0)));
        List<PrjTask> allTasks = taskMapper.selectList(null);
        data.put("totalTasks", allTasks.size());
        data.put("todoTasks", allTasks.stream().filter(t -> "TODO".equals(t.getStatus())).count());
        data.put("doingTasks", allTasks.stream().filter(t -> "DOING".equals(t.getStatus())).count());
        data.put("doneTasks", allTasks.stream().filter(t -> "DONE".equals(t.getStatus())).count());
        data.put("completionRate", allTasks.isEmpty() ? 0 :
                Math.round(1000.0 * allTasks.stream().filter(t -> "DONE".equals(t.getStatus())).count() / allTasks.size()) / 10.0);
        data.put("openDefects", defectMapper.selectCount(
                new LambdaQueryWrapper<PrjDefect>().eq(PrjDefect::getStatus, "OPEN")));
        data.put("myTodo", taskMapper.selectCount(new LambdaQueryWrapper<PrjTask>()
                .eq(PrjTask::getAssigneeUsername, me).in(PrjTask::getStatus, "TODO", "DOING")));

        // 缺陷 7 日趋势
        LocalDateTime since = LocalDate.now().minusDays(6).atStartOfDay();
        List<PrjDefect> recent = defectMapper.selectList(
                new LambdaQueryWrapper<PrjDefect>().ge(PrjDefect::getCreateTime, since));
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate day = LocalDate.now().minusDays(i);
            long count = recent.stream().filter(d ->
                    d.getCreateTime() != null && d.getCreateTime().toLocalDate().equals(day)).count();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", day.format(fmt));
            point.put("count", count);
            trend.add(point);
        }
        data.put("defectTrend7d", trend);

        // AI 模块概览
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("documents", jdbc.queryForObject(
                "SELECT COUNT(*) FROM document WHERE deleted = 0", Long.class));
        ai.put("conversations", jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation WHERE deleted = 0", Long.class));
        ai.put("gaps", jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_gap WHERE status = 'open'", Long.class));
        data.put("aiOverview", ai);

        return Result.success(data);
    }

    /** AI 用量报表（近 N 天，按日/人/模型）——方案 5.8 */
    @GetMapping("/ai-usage")
    public Result<List<Map<String, Object>>> aiUsage(@RequestParam(defaultValue = "7") int days) {
        return Result.success(jdbc.queryForList(
                "SELECT stat_date, username, model, prompt_tokens, completion_tokens, " +
                "       (prompt_tokens + completion_tokens) AS total_tokens " +
                "  FROM ai_usage WHERE stat_date >= DATE_SUB(CURRENT_DATE, INTERVAL ? DAY) " +
                " ORDER BY stat_date DESC, total_tokens DESC", days));
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
