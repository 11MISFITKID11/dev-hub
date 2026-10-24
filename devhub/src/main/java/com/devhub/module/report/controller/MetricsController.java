package com.devhub.module.report.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devhub.module.ai.common.Result;
import com.devhub.module.project.entity.PrjBurndown;
import com.devhub.module.project.mapper.PrjBurndownMapper;
import com.devhub.module.project.mapper.PrjIterationMapper;
import com.devhub.module.project.entity.PrjIteration;
import com.devhub.module.task.entity.PrjTask;
import com.devhub.module.task.mapper.PrjTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * 研发效能报表（方案 P0-3/P0-4）：
 * 周期时长 P50/P90、吞吐量、累积流图（CFD）、迭代燃尽图/速率、WIP 实时值
 * 数据全部来自本地表（task 时间戳 + burndown 快照），零外部依赖。
 */
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class MetricsController {

    private final PrjTaskMapper taskMapper;
    private final PrjBurndownMapper burndownMapper;
    private final PrjIterationMapper iterationMapper;
    private final com.devhub.module.project.mapper.PrjWorklogMapper worklogMapper;
    private final JdbcTemplate jdbc;

    /** 成员负载（P2）：未结任务数 × 本周登记工时 */
    @GetMapping("/workload")
    public Result<List<Map<String, Object>>> workload(@RequestParam(required = false) Long projectId) {
        List<PrjTask> open = taskMapper.selectList(new LambdaQueryWrapper<PrjTask>()
                .eq(projectId != null, PrjTask::getProjectId, projectId));
        LocalDate monday = LocalDate.now().with(java.time.DayOfWeek.MONDAY);
        Map<String, Double> hours = new java.util.LinkedHashMap<>();
        try {
            jdbc.queryForList("SELECT username, SUM(hours) h FROM prj_worklog WHERE deleted = 0 AND work_date >= ? GROUP BY username",
                            java.sql.Date.valueOf(monday))
                    .forEach(r -> hours.put(String.valueOf(r.get("username")),
                            r.get("h") == null ? 0.0 : ((java.math.BigDecimal) r.get("h")).doubleValue()));
        } catch (Exception ignore) { }
        Map<String, Map<String, Object>> byUser = new java.util.LinkedHashMap<>();
        for (PrjTask t : open) {
            if (t.getAssigneeUsername() == null || t.getDoneAt() != null) continue;
            Map<String, Object> m = byUser.computeIfAbsent(t.getAssigneeUsername(), k -> {
                Map<String, Object> n = new java.util.LinkedHashMap<>();
                n.put("username", k); n.put("openTasks", 0); n.put("doing", 0); n.put("hoursThisWeek", hours.getOrDefault(k, 0.0));
                return n;
            });
            m.put("openTasks", (Integer) m.get("openTasks") + 1);
            if ("DOING".equals(t.getStatus())) m.put("doing", (Integer) m.get("doing") + 1);
        }
        // 有本周工时但无未结任务的人也展示
        hours.keySet().forEach(u -> byUser.computeIfAbsent(u, k -> {
            Map<String, Object> n = new java.util.LinkedHashMap<>();
            n.put("username", k); n.put("openTasks", 0); n.put("doing", 0); n.put("hoursThisWeek", hours.get(k));
            return n;
        }));
        return Result.success(byUser.values().stream()
                .sorted((a, b) -> Integer.compare((Integer) b.get("openTasks"), (Integer) a.get("openTasks")))
                .toList());
    }

    /** 周期时长（创建→DONE）与吞吐量：近 N 周 */
    /** 问答质量看板：用户反馈、忠实度分布（近30天）、待处理缺口 TopN（数据均已在库，只缺可视化） */
    @GetMapping("/qa-quality")
    public Result<Map<String, Object>> qaQuality() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("feedbackUp", count("SELECT COUNT(*) FROM message WHERE feedback = 1"));
        out.put("feedbackDown", count("SELECT COUNT(*) FROM message WHERE feedback = -1"));
        try {
            Map<String, Object> faith = jdbc.queryForMap(
                    "SELECT COUNT(*) AS cnt, IFNULL(AVG(faith_score),0) AS avg_score, " +
                    "IFNULL(SUM(faith_score < 0.6),0) AS low_cnt " +
                    "FROM message WHERE faith_score IS NOT NULL " +
                    "AND create_time > NOW() - INTERVAL 30 DAY");
            out.put("faithCount", ((Number) faith.get("cnt")).longValue());
            out.put("faithAvg", Math.round(((Number) faith.get("avg_score")).doubleValue() * 100) / 100.0);
            out.put("faithLow", ((Number) faith.get("low_cnt")).longValue());
        } catch (Exception e) {
            out.put("faithCount", 0); out.put("faithAvg", 0); out.put("faithLow", 0);
        }
        try {
            out.put("gaps", jdbc.queryForList(
                    "SELECT question, hit_count AS hitCount FROM knowledge_gap " +
                    "WHERE status = 'open' ORDER BY hit_count DESC LIMIT 5"));
        } catch (Exception e) {
            out.put("gaps", java.util.List.of());
        }
        return Result.success(out);
    }

    private long count(String sql) {
        try {
            Long c = jdbc.queryForObject(sql, Long.class);
            return c == null ? 0 : c;
        } catch (Exception e) { return 0; }
    }

    @GetMapping("/flow")
    public Result<Map<String, Object>> flow(@RequestParam(defaultValue = "8") int weeks) {
        List<PrjTask> done = taskMapper.selectList(new LambdaQueryWrapper<PrjTask>()
                .isNotNull(PrjTask::getDoneAt)
                .ge(PrjTask::getDoneAt, LocalDate.now().minusWeeks(weeks).atStartOfDay()));

        List<Double> hours = done.stream()
                .map(t -> Math.max(0, java.time.Duration.between(t.getCreateTime(), t.getDoneAt()).toMinutes()))
                .map(min -> min / 60.0)
                .sorted()
                .toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("doneCount", hours.size());
        m.put("cycleTimeHoursP50", percentile(hours, 0.50));
        m.put("cycleTimeHoursP90", percentile(hours, 0.90));

        // 周吞吐量（按 done_at 所在周）
        Map<String, Long> throughput = new LinkedHashMap<>();
        for (int w = weeks - 1; w >= 0; w--) {
            LocalDate weekStart = LocalDate.now().minusWeeks(w).with(java.time.DayOfWeek.MONDAY);
            String label = weekStart.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"));
            long count = done.stream().filter(t -> t.getDoneAt().toLocalDate().isAfter(weekStart.minusDays(1))
                    && t.getDoneAt().toLocalDate().isBefore(weekStart.plusDays(7))).count();
            throughput.put(label, count);
        }
        m.put("throughputByWeek", throughput);

        // 累积流图（近 14 天三列堆叠：TODO/DOING/DONE 存量）
        List<Map<String, Object>> cfd = new ArrayList<>();
        for (int d = 13; d >= 0; d--) {
            LocalDate day = LocalDate.now().minusDays(d);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", day.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd")));
            point.put("todo", countCreatedBeforeAndNotDone(day, "TODO"));
            point.put("doing", countCreatedBeforeAndNotDone(day, "DOING"));
            point.put("done", countDoneBefore(day));
            cfd.add(point);
        }
        m.put("cfd", cfd);

        // 当前 WIP
        m.put("currentWip", taskMapper.selectCount(new LambdaQueryWrapper<PrjTask>()
                .eq(PrjTask::getStatus, "DOING")));
        return Result.success(m);
    }

    /** 迭代燃尽图：理想线 vs 实际线（快照缺失日自动补点） */
    @GetMapping("/iterations/{id}/burndown")
    public Result<Map<String, Object>> burndown(@PathVariable Long id) {
        PrjIteration it = iterationMapper.selectById(id);
        if (it == null) return Result.fail(404, "迭代不存在");
        List<PrjBurndown> snaps = burndownMapper.selectList(new LambdaQueryWrapper<PrjBurndown>()
                .eq(PrjBurndown::getIterationId, id).orderByAsc(PrjBurndown::getSnapshotDate));
        List<Map<String, Object>> actual = new ArrayList<>();
        snaps.forEach(s -> actual.add(Map.of(
                "date", s.getSnapshotDate().toString(),
                "open", s.getOpenTasks(), "total", s.getTotalTasks(), "done", s.getDoneTasks())));
        // 若无快照：现场按当前任务统计兜底（首日演示可用）
        if (actual.isEmpty()) {
            List<PrjTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<PrjTask>()
                    .eq(PrjTask::getIterationId, id));
            long doneC = tasks.stream().filter(t -> "DONE".equals(t.getStatus())).count();
            actual.add(Map.of("date", LocalDate.now().toString(),
                    "open", tasks.size() - (int) doneC, "total", tasks.size(), "done", (int) doneC));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("iteration", Map.of("id", id, "name", it.getName()));
        result.put("actual", actual);
        result.put("ideal", idealLine(it, actual));
        result.put("velocity", velocityOfLast(id));
        return Result.success(result);
    }

    /**
     * 理想燃尽线（方案 P0-3③）：首日总量沿迭代周期线性递减到 0。
     * 周期取迭代起止日期（缺失时用快照跨度），未到日期的点位不生成。
     */
    private List<Map<String, Object>> idealLine(PrjIteration it, List<Map<String, Object>> actual) {
        List<Map<String, Object>> ideal = new ArrayList<>();
        try {
            LocalDate start = it.getStartDate() != null ? it.getStartDate()
                    : LocalDate.parse(String.valueOf(actual.get(0).get("date")));
            LocalDate end = it.getEndDate() != null && !it.getEndDate().isBefore(start)
                    ? it.getEndDate() : start.plusDays(Math.max(1, actual.size() - 1L));
            long days = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(start, end));
            int total0 = ((Number) actual.get(0).get("total")).intValue();
            for (long i = 0; i <= days; i++) {
                LocalDate d = start.plusDays(i);
                if (d.isAfter(LocalDate.now())) break;
                ideal.add(Map.of("date", d.toString(),
                        "open", Math.round(total0 * (1.0 - (double) i / days))));
            }
        } catch (Exception e) {
            // 起止日期异常时不给理想线，前端只画实际线
        }
        return ideal;
    }

    /** 近 5 个迭代的速率（每迭代完成任务数） */
    private List<Map<String, Object>> velocityOfLast(Long currentId) {
        List<PrjIteration> its = iterationMapper.selectList(new LambdaQueryWrapper<PrjIteration>()
                .orderByDesc(PrjIteration::getId).last("LIMIT 5"));
        List<Map<String, Object>> v = new ArrayList<>();
        for (PrjIteration it : its) {
            long c = taskMapper.selectCount(new LambdaQueryWrapper<PrjTask>()
                    .eq(PrjTask::getIterationId, it.getId()).eq(PrjTask::getStatus, "DONE"));
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("iteration", it.getName());
            p.put("done", c);
            v.add(p);
        }
        return v;
    }

    private long countCreatedBeforeAndNotDone(LocalDate day, String status) {
        Long v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM prj_task WHERE deleted = 0 AND status = ? " +
                "AND create_time < ? AND (done_at IS NULL OR done_at > ?)",
                Long.class, status, day.plusDays(1).atStartOfDay(), day.plusDays(1).atStartOfDay());
        return v == null ? 0 : v;
    }

    private long countDoneBefore(LocalDate day) {
        Long v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM prj_task WHERE deleted = 0 AND done_at < ?",
                Long.class, day.plusDays(1).atStartOfDay());
        return v == null ? 0 : v;
    }

    private Object percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return null;
        int idx = (int) Math.min(sorted.size() - 1, Math.round(p * (sorted.size() - 1)));
        return sorted.get(idx);
    }
}
