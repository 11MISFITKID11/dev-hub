package com.devhub.module.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.devhub.common.OperLog;
import com.devhub.common.ProjectScoped;
import com.devhub.common.NotificationService;
import com.devhub.module.ai.common.Result;
import com.devhub.module.project.mapper.PrjProjectMapper;
import com.devhub.module.task.entity.PrjTask;
import com.devhub.module.task.mapper.PrjTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.devhub.module.project.entity.WorkflowState;

/**
 * 任务中心：CRUD + 状态机（TODO→DOING→DONE，可回退）+ 我的任务
 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final PrjTaskMapper taskMapper;
    private final PrjProjectMapper projectMapper;
    private final com.devhub.module.project.controller.CollabController collab;
    private final NotificationService notify;
    private final com.devhub.module.project.service.WorkflowEngine workflowEngine;
    private final TaskExtraController extras;
    private final com.devhub.module.project.service.AutomationService automation;
    private final com.devhub.module.project.controller.RequirementController requirements;


    @GetMapping
    public Result<Page<PrjTask>> list(@RequestParam(defaultValue = "1") long page,
                                      @RequestParam(defaultValue = "10") long size,
                                      @RequestParam(required = false) Long projectId,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) Boolean mine,
                                      @RequestParam(required = false) String keyword) {
        String me = currentUsername();
        LambdaQueryWrapper<PrjTask> q = new LambdaQueryWrapper<PrjTask>()
                .eq(projectId != null, PrjTask::getProjectId, projectId)
                .eq(status != null && !status.isBlank(), PrjTask::getStatus, status)
                .eq(Boolean.TRUE.equals(mine), PrjTask::getAssigneeUsername, me)
                .like(keyword != null && !keyword.isBlank(), PrjTask::getTitle, keyword)
                .orderByDesc(PrjTask::getUpdateTime);
        return Result.success(taskMapper.selectPage(new Page<>(page, size), q));
    }

    @OperLog(module = "任务中心", action = "创建任务")
    @PostMapping
    public Result<PrjTask> create(@RequestBody PrjTask task) {
        if (task.getStatus() == null || task.getStatus().isBlank()) {
            task.setStatus(workflowEngine.defaultStartState(task.getProjectId()));
        } else {
            String err = workflowEngine.validateState(task.getProjectId(), task.getStatus());
            if (err != null) return Result.fail(400, err);
        }
        task.setCreatorUsername(currentUsername());
        taskMapper.insert(task);
        if ("DONE".equals(task.getStatus())) task.setDoneAt(java.time.LocalDateTime.now());
        if ("DOING".equals(task.getStatus())) task.setStartAt(java.time.LocalDateTime.now());
        if (task.getStartAt() != null || task.getDoneAt() != null) taskMapper.updateById(task);
        collab.record("TASK", task.getId(), "create", null, null, task.getTitle(), task.getCreatorUsername());
        if (automation.onTaskCreated(task)) taskMapper.updateById(task); // 规则改写字段则回写
        requirements.syncStoryStatus(task.getRequirementId()); // 需求状态联动（断点1）
        return Result.success(task);
    }

    @OperLog(module = "任务中心", action = "更新任务")
    @ProjectScoped(ProjectScoped.ObjType.TASK)
    @PutMapping("/{id}")
    public Result<String> update(@PathVariable Long id, @RequestBody PrjTask task) {
        task.setId(id);
        PrjTask before = taskMapper.selectById(id);
        if (before == null) return Result.fail(404, "任务不存在");
        // 元数据驱动校验：状态合法 + 流转规则（方案 P1-5）
        if (task.getStatus() != null && !task.getStatus().isBlank()
                && !task.getStatus().equals(before.getStatus())) {
            String err = workflowEngine.validateState(before.getProjectId(), task.getStatus());
            if (err != null) return Result.fail(400, err);
            String terr = workflowEngine.validateTransition(before.getProjectId(), before.getStatus(), task.getStatus());
            if (terr != null) return Result.fail(400, terr);
            // 依赖拦截（P0-2）：流转到终态时前置任务必须全部完成
            String derr = checkDepsBeforeDone(before.getProjectId(), id, task.getStatus());
            if (derr != null) return Result.fail(400, derr);
            // WIP 限制（与 advance 同款拦截）
            WorkflowState targetDef = workflowEngine.statesOf(before.getProjectId()).stream()
                    .filter(x -> x.getName().equals(task.getStatus())).findFirst().orElse(null);
            if (targetDef != null && targetDef.getWipLimit() != null) {
                long inState = taskMapper.selectCount(new LambdaQueryWrapper<PrjTask>()
                        .eq(PrjTask::getProjectId, before.getProjectId())
                        .eq(PrjTask::getStatus, task.getStatus()));
                if (inState >= targetDef.getWipLimit()) {
                    return Result.fail(400, "WIP 限制：状态「" + task.getStatus() + "」已有 " + inState
                            + " 个任务（上限 " + targetDef.getWipLimit() + "）");
                }
            }
        }
        boolean statusChanged = task.getStatus() != null && !task.getStatus().equals(before.getStatus());
        boolean assignChanged = task.getAssigneeUsername() != null && !task.getAssigneeUsername().equals(before.getAssigneeUsername());
        if (statusChanged) {
            collab.record("TASK", id, "status", "status", before.getStatus(), task.getStatus(), null);
            if ("DONE".equals(task.getStatus()) && before.getDoneAt() == null) task.setDoneAt(java.time.LocalDateTime.now());
            if ("DOING".equals(task.getStatus()) && before.getStartAt() == null) task.setStartAt(java.time.LocalDateTime.now());
        }
        if (assignChanged) {
            collab.record("TASK", id, "assign", "assignee", before.getAssigneeUsername(), task.getAssigneeUsername(), null);
            notify.push(task.getAssigneeUsername(), "assign", "你被指派了任务", before.getTitle(),
                    "/#/task");
        }
        if (task.getPriority() != null && !task.getPriority().equals(before.getPriority())) {
            collab.record("TASK", id, "field", "priority", before.getPriority(), task.getPriority(), null);
        }
        taskMapper.updateById(task);
        // 自动化规则（P2）：重载完整实体供规则读取标题等字段；改写字段则回写
        PrjTask fresh = taskMapper.selectById(id);
        boolean mutated = false;
        if (statusChanged) mutated |= automation.onStatusChanged(fresh, task.getStatus());
        if (assignChanged) mutated |= automation.onAssigneeChanged(fresh);
        if (mutated) taskMapper.updateById(fresh);
        requirements.syncStoryStatus(fresh.getRequirementId()); // 需求状态联动（断点1）
        return Result.success("ok");
    }

    @OperLog(module = "任务中心", action = "删除任务")
    @ProjectScoped(ProjectScoped.ObjType.TASK)
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        taskMapper.deleteById(id);
        return Result.success("ok");
    }

    /** 看板：按状态分组（列随项目工作流动态定义，方案 P1-5） */
    @GetMapping("/board")
    public Result<Map<String, List<PrjTask>>> board(@RequestParam(required = false) Long projectId) {
        List<PrjTask> all = taskMapper.selectList(new LambdaQueryWrapper<PrjTask>()
                .eq(projectId != null, PrjTask::getProjectId, projectId)
                .orderByDesc(PrjTask::getUpdateTime));
        Map<String, List<PrjTask>> grouped = new java.util.LinkedHashMap<>();
        if (projectId != null) {
            for (WorkflowState s : workflowEngine.statesOf(projectId)) {
                grouped.put(s.getName(), new java.util.ArrayList<>());
            }
        } else {
            grouped.put("TODO", new java.util.ArrayList<>());
            grouped.put("DOING", new java.util.ArrayList<>());
            grouped.put("DONE", new java.util.ArrayList<>());
        }
        for (PrjTask t : all) {
            grouped.computeIfAbsent(t.getStatus(), k -> new java.util.ArrayList<>()).add(t);
        }
        return Result.success(grouped);
    }

    /** 状态推进（元数据驱动：目标状态由项目工作流规则决定 + WIP 拦截） */
    @OperLog(module = "任务中心", action = "推进状态")
    @ProjectScoped(ProjectScoped.ObjType.TASK)
    @PostMapping("/{id}/advance")
    public Result<String> advance(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean forward) {
        PrjTask task = taskMapper.selectById(id);
        if (task == null) return Result.fail(404, "任务不存在");
        String next = forward ? workflowEngine.nextForwardState(task.getProjectId(), task.getStatus())
                : workflowEngine.nextBackState(task.getProjectId(), task.getStatus());
        if (next == null) {
            return Result.fail(400, (forward ? "已是终态" : "已是初始态") + "：" + task.getStatus());
        }
        // WIP 限制：目标状态超限则拦截（除非任务已在该状态）
        WorkflowState targetDef = workflowEngine.statesOf(task.getProjectId()).stream()
                .filter(s -> s.getName().equals(next)).findFirst().orElse(null);
        if (targetDef != null && targetDef.getWipLimit() != null && !next.equals(task.getStatus())) {
            long inState = taskMapper.selectCount(new LambdaQueryWrapper<PrjTask>()
                    .eq(PrjTask::getProjectId, task.getProjectId())
                    .eq(PrjTask::getStatus, next));
            if (inState >= targetDef.getWipLimit()) {
                return Result.fail(400, "WIP 限制：状态「" + next + "」已有 " + inState
                        + " 个任务（上限 " + targetDef.getWipLimit() + "），先完成在办任务");
            }
        }
        String prev = task.getStatus();
        // 依赖拦截（P0-2）：推进到终态时前置任务必须全部完成
        String derr = checkDepsBeforeDone(task.getProjectId(), id, next);
        if (derr != null) return Result.fail(400, derr);
        task.setStatus(next);
        if ("DONE".equals(next) && task.getDoneAt() == null) task.setDoneAt(java.time.LocalDateTime.now());
        if ("DOING".equals(next) && task.getStartAt() == null) task.setStartAt(java.time.LocalDateTime.now());
        taskMapper.updateById(task);
        collab.record("TASK", id, "status", "status", prev, next, null);
        if (automation.onStatusChanged(task, next)) taskMapper.updateById(task);
        requirements.syncStoryStatus(task.getRequirementId()); // 需求状态联动（断点1）
        return Result.success(next);
    }

    /** 目标状态属终态（category=DONE）时校验前置依赖 */
    private String checkDepsBeforeDone(Long projectId, Long taskId, String targetStatus) {
        WorkflowState def = workflowEngine.statesOf(projectId).stream()
                .filter(s -> s.getName().equals(targetStatus)).findFirst().orElse(null);
        if (def == null || !"DONE".equals(def.getCategory())) return null;
        List<String> blocked = extras.unfinishedDeps(taskId, projectId);
        if (!blocked.isEmpty()) {
            return "前置任务未完成，无法结项：" + String.join("；", blocked);
        }
        return null;
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
