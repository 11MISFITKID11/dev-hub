package com.devhub.module.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.devhub.common.NotificationService;
import com.devhub.module.ai.common.BusinessException;
import com.devhub.module.project.entity.PrjTaskDep;
import com.devhub.module.project.entity.WorkflowState;
import com.devhub.module.project.mapper.PrjTaskDepMapper;
import com.devhub.module.project.service.ActivityRecorder;
import com.devhub.module.project.service.AutomationService;
import com.devhub.module.project.service.RequirementStatusService;
import com.devhub.module.project.service.WorkflowEngine;
import com.devhub.module.task.entity.PrjTask;
import com.devhub.module.task.mapper.PrjTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务中心服务（从 TaskController 抽出的业务层，消除 Controller 互调）
 * <p>
 * 写路径 @Transactional：任务落库、动态记录、自动化规则、需求状态联动同事务，
 * 任一步失败整体回滚，不再出现"任务插了但活动流缺失"的中间态。
 * <p>
 * 并发控制两层：@Version 乐观锁（update/updateById 携带读取时版本）+
 * advance 的状态条件更新（仅当状态仍为读取值时推进），冲突返回 409。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final PrjTaskMapper taskMapper;
    private final PrjTaskDepMapper depMapper;
    private final WorkflowEngine workflowEngine;
    private final AutomationService automation;
    private final NotificationService notify;
    private final ActivityRecorder activityRecorder;
    private final RequirementStatusService requirementStatusService;

    /** 分页查询（可按项目/状态/我的/关键字过滤） */
    public Page<PrjTask> list(long page, long size, Long projectId, String status,
                              Boolean mine, String keyword, String username) {
        LambdaQueryWrapper<PrjTask> q = new LambdaQueryWrapper<PrjTask>()
                .eq(projectId != null, PrjTask::getProjectId, projectId)
                .eq(status != null && !status.isBlank(), PrjTask::getStatus, status)
                .eq(Boolean.TRUE.equals(mine), PrjTask::getAssigneeUsername, username)
                .like(keyword != null && !keyword.isBlank(), PrjTask::getTitle, keyword)
                .orderByDesc(PrjTask::getUpdateTime);
        return taskMapper.selectPage(new Page<>(page, size), q);
    }

    /** 看板：按状态分组（列随项目工作流动态定义） */
    public Map<String, List<PrjTask>> board(Long projectId) {
        List<PrjTask> all = taskMapper.selectList(new LambdaQueryWrapper<PrjTask>()
                .eq(projectId != null, PrjTask::getProjectId, projectId)
                .orderByDesc(PrjTask::getUpdateTime));
        Map<String, List<PrjTask>> grouped = new LinkedHashMap<>();
        if (projectId != null) {
            for (WorkflowState s : workflowEngine.statesOf(projectId)) {
                grouped.put(s.getName(), new ArrayList<>());
            }
        } else {
            grouped.put("TODO", new ArrayList<>());
            grouped.put("DOING", new ArrayList<>());
            grouped.put("DONE", new ArrayList<>());
        }
        for (PrjTask t : all) {
            grouped.computeIfAbsent(t.getStatus(), k -> new ArrayList<>()).add(t);
        }
        return grouped;
    }

    /** 创建任务：校验状态合法 → 落库 → 动态 → 自动化 → 需求联动（同事务） */
    @Transactional(rollbackFor = Exception.class)
    public PrjTask create(PrjTask task, String operator) {
        if (task.getStatus() == null || task.getStatus().isBlank()) {
            task.setStatus(workflowEngine.defaultStartState(task.getProjectId()));
        } else {
            String err = workflowEngine.validateState(task.getProjectId(), task.getStatus());
            if (err != null) throw new BusinessException(err);
        }
        task.setCreatorUsername(operator);
        taskMapper.insert(task);
        if ("DONE".equals(task.getStatus())) task.setDoneAt(LocalDateTime.now());
        if ("DOING".equals(task.getStatus())) task.setStartAt(LocalDateTime.now());
        if (task.getStartAt() != null || task.getDoneAt() != null) taskMapper.updateById(task);
        activityRecorder.record("TASK", task.getId(), "create", null, null, task.getTitle(), task.getCreatorUsername());
        if (automation.onTaskCreated(task)) taskMapper.updateById(task);
        requirementStatusService.syncStoryStatus(task.getRequirementId());
        return task;
    }

    /** 更新任务：状态流转校验（合法状态+流转规则+依赖+WIP）→ 乐观锁提交 */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, PrjTask task) {
        PrjTask before = taskMapper.selectById(id);
        if (before == null) throw new BusinessException(404, "任务不存在");
        boolean statusChanged = task.getStatus() != null && !task.getStatus().isBlank()
                && !task.getStatus().equals(before.getStatus());
        if (statusChanged) {
            String err = workflowEngine.validateState(before.getProjectId(), task.getStatus());
            if (err != null) throw new BusinessException(err);
            String terr = workflowEngine.validateTransition(before.getProjectId(), before.getStatus(), task.getStatus());
            if (terr != null) throw new BusinessException(terr);
            String derr = checkDepsBeforeDone(before.getProjectId(), id, task.getStatus());
            if (derr != null) throw new BusinessException(derr);
            WorkflowState targetDef = workflowEngine.statesOf(before.getProjectId()).stream()
                    .filter(x -> x.getName().equals(task.getStatus())).findFirst().orElse(null);
            if (targetDef != null && targetDef.getWipLimit() != null) {
                long inState = taskMapper.selectCount(new LambdaQueryWrapper<PrjTask>()
                        .eq(PrjTask::getProjectId, before.getProjectId())
                        .eq(PrjTask::getStatus, task.getStatus()));
                if (inState >= targetDef.getWipLimit()) {
                    throw new BusinessException("WIP 限制：状态「" + task.getStatus() + "」已有 " + inState
                            + " 个任务（上限 " + targetDef.getWipLimit() + "）");
                }
            }
        }
        boolean assignChanged = task.getAssigneeUsername() != null
                && !task.getAssigneeUsername().equals(before.getAssigneeUsername());
        if (statusChanged) {
            activityRecorder.record("TASK", id, "status", "status", before.getStatus(), task.getStatus(), null);
            if ("DONE".equals(task.getStatus()) && before.getDoneAt() == null) task.setDoneAt(LocalDateTime.now());
            if ("DOING".equals(task.getStatus()) && before.getStartAt() == null) task.setStartAt(LocalDateTime.now());
        }
        if (assignChanged) {
            activityRecorder.record("TASK", id, "assign", "assignee",
                    before.getAssigneeUsername(), task.getAssigneeUsername(), null);
            notify.push(task.getAssigneeUsername(), "assign", "你被指派了任务", before.getTitle(), "/#/task");
        }
        if (task.getPriority() != null && !task.getPriority().equals(before.getPriority())) {
            activityRecorder.record("TASK", id, "field", "priority",
                    before.getPriority(), task.getPriority(), null);
        }
        task.setId(id);
        // 乐观锁：以读取时版本提交，并发修改方更新 0 行
        task.setVersion(before.getVersion());
        if (taskMapper.updateById(task) == 0) {
            throw new BusinessException(409, "任务已被他人修改，请刷新后重试");
        }
        // 自动化规则：重载完整实体供规则读取；改写字段则回写（携带新版本）
        PrjTask fresh = taskMapper.selectById(id);
        boolean mutated = false;
        if (statusChanged) mutated |= automation.onStatusChanged(fresh, task.getStatus());
        if (assignChanged) mutated |= automation.onAssigneeChanged(fresh);
        if (mutated) taskMapper.updateById(fresh);
        requirementStatusService.syncStoryStatus(fresh.getRequirementId());
    }

    /** 删除任务（@TableLogic 软删） */
    public void delete(Long id) {
        taskMapper.deleteById(id);
    }

    /**
     * 状态推进（元数据驱动：目标状态由项目工作流规则决定 + WIP/依赖拦截）。
     * CAS 条件更新：仅当状态仍为读取值且版本一致时推进，并发推进只有一个成功。
     */
    @Transactional(rollbackFor = Exception.class)
    public String advance(Long id, boolean forward) {
        PrjTask task = taskMapper.selectById(id);
        if (task == null) throw new BusinessException(404, "任务不存在");
        String next = forward ? workflowEngine.nextForwardState(task.getProjectId(), task.getStatus())
                : workflowEngine.nextBackState(task.getProjectId(), task.getStatus());
        if (next == null) {
            throw new BusinessException(400, (forward ? "已是终态" : "已是初始态") + "：" + task.getStatus());
        }
        // WIP 限制：目标状态超限则拦截（计数存在毫秒级竞态窗口，由下方 CAS 与乐观锁兜底）
        WorkflowState targetDef = workflowEngine.statesOf(task.getProjectId()).stream()
                .filter(s -> s.getName().equals(next)).findFirst().orElse(null);
        if (targetDef != null && targetDef.getWipLimit() != null && !next.equals(task.getStatus())) {
            long inState = taskMapper.selectCount(new LambdaQueryWrapper<PrjTask>()
                    .eq(PrjTask::getProjectId, task.getProjectId())
                    .eq(PrjTask::getStatus, next));
            if (inState >= targetDef.getWipLimit()) {
                throw new BusinessException("WIP 限制：状态「" + next + "」已有 " + inState
                        + " 个任务（上限 " + targetDef.getWipLimit() + "），先完成在办任务");
            }
        }
        String prev = task.getStatus();
        // 依赖拦截：推进到终态时前置任务必须全部完成
        String derr = checkDepsBeforeDone(task.getProjectId(), id, next);
        if (derr != null) throw new BusinessException(derr);

        // CAS：UPDATE ... SET status=next WHERE id=? AND status=prev AND version=?
        PrjTask patch = new PrjTask();
        patch.setId(id);
        patch.setStatus(next);
        patch.setVersion(task.getVersion());
        if ("DONE".equals(next) && task.getDoneAt() == null) patch.setDoneAt(LocalDateTime.now());
        if ("DOING".equals(next) && task.getStartAt() == null) patch.setStartAt(LocalDateTime.now());
        int updated = taskMapper.update(patch, new LambdaUpdateWrapper<PrjTask>()
                .eq(PrjTask::getId, id)
                .eq(PrjTask::getStatus, prev));
        if (updated == 0) {
            throw new BusinessException(409, "任务状态已被他人变更，请刷新后重试");
        }
        activityRecorder.record("TASK", id, "status", "status", prev, next, null);
        PrjTask fresh = taskMapper.selectById(id);
        if (automation.onStatusChanged(fresh, next)) taskMapper.updateById(fresh);
        requirementStatusService.syncStoryStatus(fresh.getRequirementId());
        return next;
    }

    /** 前置依赖中未到终态的任务清单（供推进拦截与依赖端点共用） */
    public List<String> unfinishedDeps(Long taskId, Long projectId) {
        java.util.Set<String> doneStates = workflowEngine.statesOf(projectId).stream()
                .filter(s -> "DONE".equals(s.getCategory()))
                .map(WorkflowState::getName).collect(java.util.stream.Collectors.toSet());
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

    /** 目标状态属终态（category=DONE）时校验前置依赖 */
    private String checkDepsBeforeDone(Long projectId, Long taskId, String targetStatus) {
        WorkflowState def = workflowEngine.statesOf(projectId).stream()
                .filter(s -> s.getName().equals(targetStatus)).findFirst().orElse(null);
        if (def == null || !"DONE".equals(def.getCategory())) return null;
        List<String> blocked = unfinishedDeps(taskId, projectId);
        if (!blocked.isEmpty()) {
            return "前置任务未完成，无法结项：" + String.join("；", blocked);
        }
        return null;
    }
}
