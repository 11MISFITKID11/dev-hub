package com.devhub.module.project.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.devhub.common.OperLog;
import com.devhub.common.ProjectScoped;
import com.devhub.module.ai.common.Result;
import com.devhub.module.project.entity.PrjRequirement;
import com.devhub.module.project.mapper.PrjRequirementMapper;
import com.devhub.module.project.mapper.PrjIterationMapper;
import com.devhub.module.task.entity.PrjTask;
import com.devhub.module.task.mapper.PrjTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 需求管理（P0-1，对标 Jira/Linear 的 Epic → Story 骨架）：
 * Epic 是分组容器，Story 承载需求明细并可挂任务/迭代。
 */
@RestController
@RequestMapping("/api/requirements")
@RequiredArgsConstructor
public class RequirementController {

    private static final Set<String> VALID_TYPES = Set.of("EPIC", "STORY");
    private static final Set<String> VALID_STATUS = Set.of("OPEN", "DEVELOPING", "DONE", "REJECTED");

    private final PrjRequirementMapper reqMapper;
    private final PrjIterationMapper iterationMapper;
    private final PrjTaskMapper taskMapper;
    private final com.devhub.module.project.service.WorkflowEngine workflowEngine;

    /**
     * 需求状态联动（闭环断点1补齐）：Story 下任务全部到达项目工作流终态 → DONE；
     * 存在非终态任务 → DEVELOPING（含任务回退后从 DONE 回归）。REJECTED 不被联动覆盖。
     * 由任务创建/状态变更调用，失败不影响任务主流程。
     */
    public void syncStoryStatus(Long requirementId) {
        if (requirementId == null) return;
        try {
            PrjRequirement story = reqMapper.selectById(requirementId);
            if (story == null || !"STORY".equals(story.getRtype()) || "REJECTED".equals(story.getStatus())) return;
            List<PrjTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<PrjTask>()
                    .eq(PrjTask::getRequirementId, requirementId));
            if (tasks.isEmpty()) return;
            java.util.Set<String> doneStates = workflowEngine.statesOf(story.getProjectId()).stream()
                    .filter(s -> "DONE".equals(s.getCategory()))
                    .map(com.devhub.module.project.entity.WorkflowState::getName)
                    .collect(Collectors.toSet());
            boolean allDone = tasks.stream().allMatch(t -> doneStates.contains(t.getStatus()));
            String target = allDone ? "DONE" : "DEVELOPING";
            if (!target.equals(story.getStatus())) {
                PrjRequirement patch = new PrjRequirement();
                patch.setId(requirementId);
                patch.setStatus(target);
                reqMapper.updateById(patch);
            }
        } catch (Exception ignore) {
            // 联动失败不影响任务主流程
        }
    }

    /** Story 分页列表（可按项目/状态/Epic/关键字过滤） */
    @GetMapping
    public Result<Page<PrjRequirement>> list(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) Long projectId,
                                             @RequestParam(defaultValue = "STORY") String rtype,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) Long epicId,
                                             @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<PrjRequirement> q = new LambdaQueryWrapper<PrjRequirement>()
                .eq(projectId != null, PrjRequirement::getProjectId, projectId)
                .eq(PrjRequirement::getRtype, VALID_TYPES.contains(rtype) ? rtype : "STORY")
                .eq(status != null && !status.isBlank(), PrjRequirement::getStatus, status)
                .eq(epicId != null, PrjRequirement::getParentId, epicId)
                .like(keyword != null && !keyword.isBlank(), PrjRequirement::getTitle, keyword)
                .orderByDesc(PrjRequirement::getUpdateTime);
        return Result.success(reqMapper.selectPage(new Page<>(page, size), q));
    }

    /** 项目的全部 Epic（下拉与进度卡用） */
    @GetMapping("/epics")
    public Result<List<PrjRequirement>> epics(@RequestParam(required = false) Long projectId) {
        return Result.success(reqMapper.selectList(new LambdaQueryWrapper<PrjRequirement>()
                .eq(projectId != null, PrjRequirement::getProjectId, projectId)
                .eq(PrjRequirement::getRtype, "EPIC")
                .orderByAsc(PrjRequirement::getId)));
    }

    /** Epic 进度概览：每个 Epic 下 Story 的状态计数 */
    @GetMapping("/epic-progress")
    public Result<List<Map<String, Object>>> epicProgress(@RequestParam Long projectId) {
        List<PrjRequirement> stories = reqMapper.selectList(new LambdaQueryWrapper<PrjRequirement>()
                .eq(PrjRequirement::getProjectId, projectId).eq(PrjRequirement::getRtype, "STORY"));
        List<PrjRequirement> epics = reqMapper.selectList(new LambdaQueryWrapper<PrjRequirement>()
                .eq(PrjRequirement::getProjectId, projectId).eq(PrjRequirement::getRtype, "EPIC"));
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (PrjRequirement e : epics) {
            List<PrjRequirement> mine = stories.stream()
                    .filter(s -> e.getId().equals(s.getParentId())).toList();
            long done = mine.stream().filter(s -> "DONE".equals(s.getStatus())).count();
            out.add(Map.of("id", e.getId(), "name", e.getTitle(),
                    "total", mine.size(), "done", done,
                    "percent", mine.isEmpty() ? 0 : Math.round(done * 100.0 / mine.size())));
        }
        return Result.success(out);
    }

    /** Epic 下关联任务（Story 挂的任务） */
    @GetMapping("/{id}/tasks")
    public Result<?> tasksOf(@PathVariable Long id) {
        PrjRequirement r = reqMapper.selectById(id);
        if (r == null) return Result.fail(404, "需求不存在");
        return Result.success(taskMapper.selectList(new LambdaQueryWrapper<com.devhub.module.task.entity.PrjTask>()
                .eq(com.devhub.module.task.entity.PrjTask::getRequirementId, id)
                .orderByDesc(com.devhub.module.task.entity.PrjTask::getUpdateTime)));
    }

    @OperLog(module = "需求管理", action = "创建需求")
    @PostMapping
    public Result<PrjRequirement> create(@RequestBody PrjRequirement r) {
        if (!VALID_TYPES.contains(r.getRtype() == null ? "STORY" : r.getRtype())) {
            return Result.fail(400, "rtype 必须为 EPIC/STORY");
        }
        if (r.getTitle() == null || r.getTitle().isBlank()) return Result.fail(400, "标题不能为空");
        if (r.getProjectId() == null) return Result.fail(400, "必须选择项目");
        if (r.getStatus() == null || !VALID_STATUS.contains(r.getStatus())) r.setStatus("OPEN");
        if ("STORY".equals(r.getRtype()) && r.getParentId() != null) {
            PrjRequirement epic = reqMapper.selectById(r.getParentId());
            if (epic == null || !"EPIC".equals(epic.getRtype())) return Result.fail(400, "所属 Epic 不存在");
        }
        if ("EPIC".equals(r.getRtype())) r.setParentId(null);
        r.setOwnerUsername(r.getOwnerUsername() == null ? currentUsername() : r.getOwnerUsername());
        reqMapper.insert(r);
        return Result.success(r);
    }

    @OperLog(module = "需求管理", action = "更新需求")
    @ProjectScoped(value = ProjectScoped.ObjType.REQUIREMENT)
    @PutMapping("/{id}")
    public Result<String> update(@PathVariable Long id, @RequestBody PrjRequirement r) {
        if (r.getStatus() != null && !VALID_STATUS.contains(r.getStatus())) {
            return Result.fail(400, "非法需求状态");
        }
        r.setId(id);
        r.setRtype(null); // 类型不可变更
        reqMapper.updateById(r);
        return Result.success("ok");
    }

    @OperLog(module = "需求管理", action = "删除需求")
    @ProjectScoped(value = ProjectScoped.ObjType.REQUIREMENT)
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        // Epic 删除时其下 Story 解除分组（不级联删除）
        reqMapper.selectList(new LambdaQueryWrapper<PrjRequirement>()
                        .eq(PrjRequirement::getParentId, id))
                .forEach(s -> {
                    PrjRequirement patch = new PrjRequirement();
                    patch.setId(s.getId());
                    patch.setParentId(null);
                    reqMapper.updateById(patch);
                });
        reqMapper.deleteById(id);
        return Result.success("ok");
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
