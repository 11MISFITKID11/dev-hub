package com.devhub.module.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devhub.common.OperLog;
import com.devhub.common.ProjectScoped;
import com.devhub.module.ai.common.Result;
import com.devhub.module.project.entity.PrjSubtask;
import com.devhub.module.project.entity.PrjTaskDep;
import com.devhub.module.project.entity.WorkflowState;
import com.devhub.module.project.mapper.PrjSubtaskMapper;
import com.devhub.module.project.mapper.PrjTaskDepMapper;
import com.devhub.module.project.mapper.PrjWorklogMapper;
import com.devhub.module.project.entity.PrjWorklog;
import com.devhub.module.project.service.WorkflowEngine;
import com.devhub.module.task.entity.PrjTask;
import com.devhub.module.task.mapper.PrjTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 任务扩展（P0-2）：子任务清单 + 依赖关系（blocked-by / blocking）。
 * 依赖语义：前置任务未到终态时，本任务不允许推进到终态。
 */
@RestController
@RequestMapping("/api/tasks/{taskId}")
@RequiredArgsConstructor
public class TaskExtraController {

    private final PrjTaskMapper taskMapper;
    private final PrjSubtaskMapper subtaskMapper;
    private final PrjTaskDepMapper depMapper;
    private final PrjWorklogMapper worklogMapper;
    private final WorkflowEngine workflowEngine;

    // ---------- 子任务清单 ----------

    @GetMapping("/subtasks")
    public Result<List<PrjSubtask>> subtasks(@PathVariable Long taskId) {
        return Result.success(subtaskMapper.selectList(new LambdaQueryWrapper<PrjSubtask>()
                .eq(PrjSubtask::getTaskId, taskId).orderByAsc(PrjSubtask::getId)));
    }

    @OperLog(module = "任务中心", action = "添加子任务")
    @ProjectScoped(ProjectScoped.ObjType.TASK)
    @PostMapping("/subtasks")
    public Result<PrjSubtask> addSubtask(@PathVariable Long taskId, @RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "").trim();
        if (title.isEmpty()) return Result.fail(400, "子任务标题不能为空");
        PrjSubtask s = new PrjSubtask();
        s.setTaskId(taskId); s.setTitle(title); s.setDone(0);
        subtaskMapper.insert(s);
        return Result.success(s);
    }

    @OperLog(module = "任务中心", action = "更新子任务")
    @ProjectScoped(ProjectScoped.ObjType.TASK)
    @PutMapping("/subtasks/{sid}")
    public Result<String> toggleSubtask(@PathVariable Long taskId, @PathVariable Long sid,
                                        @RequestBody Map<String, Object> body) {
        PrjSubtask s = subtaskMapper.selectById(sid);
        if (s == null || !s.getTaskId().equals(taskId)) return Result.fail(404, "子任务不存在");
        if (body.containsKey("done")) s.setDone(Boolean.TRUE.equals(body.get("done")) ? 1 : 0);
        if (body.containsKey("title")) s.setTitle(String.valueOf(body.get("title")));
        subtaskMapper.updateById(s);
        return Result.success("ok");
    }

    @OperLog(module = "任务中心", action = "删除子任务")
    @ProjectScoped(ProjectScoped.ObjType.TASK)
    @DeleteMapping("/subtasks/{sid}")
    public Result<String> delSubtask(@PathVariable Long taskId, @PathVariable Long sid) {
        subtaskMapper.deleteById(sid);
        return Result.success("ok");
    }

    // ---------- 工时登记（P2） ----------

    @GetMapping("/worklogs")
    public Result<List<PrjWorklog>> worklogs(@PathVariable Long taskId) {
        return Result.success(worklogMapper.selectList(new LambdaQueryWrapper<PrjWorklog>()
                .eq(PrjWorklog::getTaskId, taskId).orderByDesc(PrjWorklog::getWorkDate)));
    }

    @OperLog(module = "任务中心", action = "登记工时")
    @ProjectScoped(ProjectScoped.ObjType.TASK)
    @PostMapping("/worklogs")
    public Result<PrjWorklog> addWorklog(@PathVariable Long taskId, @RequestBody Map<String, Object> body) {
        PrjWorklog w = new PrjWorklog();
        w.setTaskId(taskId);
        w.setHours(new java.math.BigDecimal(String.valueOf(body.get("hours"))));
        if (w.getHours() == null || w.getHours().doubleValue() <= 0 || w.getHours().doubleValue() > 24) {
            return Result.fail(400, "工时需在 0~24 之间");
        }
        w.setWorkDate(body.get("workDate") != null ? java.time.LocalDate.parse(String.valueOf(body.get("workDate")))
                : java.time.LocalDate.now());
        w.setNote(body.get("note") == null ? null : String.valueOf(body.get("note")));
        w.setUsername(org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName());
        worklogMapper.insert(w);
        return Result.success(w);
    }

    @OperLog(module = "任务中心", action = "删除工时")
    @ProjectScoped(ProjectScoped.ObjType.TASK)
    @DeleteMapping("/worklogs/{wid}")
    public Result<String> delWorklog(@PathVariable Long taskId, @PathVariable Long wid) {
        PrjWorklog w = worklogMapper.selectById(wid);
        if (w != null && w.getTaskId().equals(taskId)) worklogMapper.deleteById(wid);
        return Result.success("ok");
    }

    // ---------- 依赖关系 ----------

    /** blockedBy（前置）与 blocking（被我阻塞）双向视图 */
    @GetMapping("/deps")
    public Result<Map<String, Object>> deps(@PathVariable Long taskId) {
        List<Long> blockedByIds = depMapper.selectList(new LambdaQueryWrapper<PrjTaskDep>()
                        .eq(PrjTaskDep::getTaskId, taskId))
                .stream().map(PrjTaskDep::getDependsOn).toList();
        List<Long> blockingIds = depMapper.selectList(new LambdaQueryWrapper<PrjTaskDep>()
                        .eq(PrjTaskDep::getDependsOn, taskId))
                .stream().map(PrjTaskDep::getTaskId).toList();
        return Result.success(Map.of(
                "blockedBy", briefOf(blockedByIds, taskId),
                "blocking", briefOf(blockingIds, taskId),
                // dep 表 id 供删除
                "depIds", depMapper.selectList(new LambdaQueryWrapper<PrjTaskDep>()
                                .eq(PrjTaskDep::getTaskId, taskId))
                        .stream().collect(Collectors.toMap(PrjTaskDep::getDependsOn, PrjTaskDep::getId))));
    }

    @OperLog(module = "任务中心", action = "添加依赖")
    @ProjectScoped(ProjectScoped.ObjType.TASK)
    @PostMapping("/deps")
    public Result<String> addDep(@PathVariable Long taskId, @RequestBody Map<String, Object> body) {
        Long dependsOn = Long.valueOf(String.valueOf(body.get("dependsOn")));
        if (dependsOn.equals(taskId)) return Result.fail(400, "任务不能依赖自身");
        PrjTask me = taskMapper.selectById(taskId);
        PrjTask pre = taskMapper.selectById(dependsOn);
        if (me == null || pre == null) return Result.fail(404, "任务不存在");
        if (!me.getProjectId().equals(pre.getProjectId())) return Result.fail(400, "只能依赖同项目的任务");
        // 环检测：dependsOn 的祖先链上不能包含 taskId
        if (wouldCycle(taskId, dependsOn)) return Result.fail(400, "不允许形成循环依赖");
        Long exists = depMapper.selectCount(new LambdaQueryWrapper<PrjTaskDep>()
                .eq(PrjTaskDep::getTaskId, taskId).eq(PrjTaskDep::getDependsOn, dependsOn));
        if (exists > 0) return Result.fail(400, "该依赖已存在");
        PrjTaskDep d = new PrjTaskDep();
        d.setTaskId(taskId); d.setDependsOn(dependsOn);
        depMapper.insert(d);
        return Result.success("ok");
    }

    @OperLog(module = "任务中心", action = "移除依赖")
    @ProjectScoped(ProjectScoped.ObjType.TASK)
    @DeleteMapping("/deps/{depId}")
    public Result<String> delDep(@PathVariable Long taskId, @PathVariable Long depId) {
        PrjTaskDep d = depMapper.selectById(depId);
        if (d != null && d.getTaskId().equals(taskId)) depMapper.deleteById(depId);
        return Result.success("ok");
    }

    /** 供 TaskController 推进校验：未完成的前置任务列表 */
    public List<String> unfinishedDeps(Long taskId, Long projectId) {
        Set<String> doneStates = workflowEngine.statesOf(projectId).stream()
                .filter(s -> "DONE".equals(s.getCategory()))
                .map(WorkflowState::getName).collect(Collectors.toSet());
        List<Long> ids = depMapper.selectList(new LambdaQueryWrapper<PrjTaskDep>()
                        .eq(PrjTaskDep::getTaskId, taskId))
                .stream().map(PrjTaskDep::getDependsOn).toList();
        List<String> blocked = new ArrayList<>();
        for (Long id : ids) {
            PrjTask t = taskMapper.selectById(id);
            if (t != null && !doneStates.contains(t.getStatus())) blocked.add("#" + id + " " + t.getTitle());
        }
        return blocked;
    }

    private boolean wouldCycle(Long taskId, Long dependsOn) {
        // 沿 dependsOn 的前置链向上找，若回到 taskId 则成环
        Long cur = dependsOn;
        int guard = 0;
        while (cur != null && guard++ < 100) {
            if (cur.equals(taskId)) return true;
            List<Long> ups = depMapper.selectList(new LambdaQueryWrapper<PrjTaskDep>()
                            .eq(PrjTaskDep::getTaskId, cur))
                    .stream().map(PrjTaskDep::getDependsOn).toList();
            cur = ups.isEmpty() ? null : ups.get(0);
        }
        return false;
    }

    private List<Map<String, Object>> briefOf(List<Long> ids, Long excludeId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : ids) {
            if (id.equals(excludeId)) continue;
            PrjTask t = taskMapper.selectById(id);
            if (t != null) out.add(Map.of("id", t.getId(), "title", t.getTitle(), "status", t.getStatus()));
        }
        return out;
    }
}
