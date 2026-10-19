package com.devhub.module.project.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.devhub.common.OperLog;
import com.devhub.common.ProjectScoped;
import com.devhub.common.NotificationService;
import com.devhub.module.ai.common.Result;
import com.devhub.module.project.entity.PrjIteration;
import com.devhub.module.project.entity.PrjMember;
import com.devhub.module.project.entity.PrjProject;
import com.devhub.module.project.entity.WorkflowState;
import com.devhub.module.project.mapper.PrjIterationMapper;
import com.devhub.module.project.mapper.PrjMemberMapper;
import com.devhub.module.project.mapper.PrjProjectMapper;
import com.devhub.module.task.entity.PrjTask;
import com.devhub.module.task.mapper.PrjTaskMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目管理：项目 CRUD + 成员 + 迭代（P0）+ 迭代关闭（P0-3）
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final PrjProjectMapper projectMapper;
    private final PrjMemberMapper memberMapper;
    private final PrjIterationMapper iterationMapper;
    private final PrjTaskMapper taskMapper;
    private final com.devhub.module.project.service.WorkflowEngine workflowEngine;
    private final com.devhub.module.project.controller.CollabController collab;
    private final NotificationService notify;

    @GetMapping
    public Result<Page<PrjProject>> list(@RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "10") long size,
                                         @RequestParam(required = false) Integer status,
                                         @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<PrjProject> q = new LambdaQueryWrapper<PrjProject>()
                .eq(status != null, PrjProject::getStatus, status)
                .like(keyword != null && !keyword.isBlank(), PrjProject::getName, keyword)
                .orderByDesc(PrjProject::getCreateTime);
        return Result.success(projectMapper.selectPage(new Page<>(page, size), q));
    }

    /** 下拉选项（任务/缺陷表单用） */
    @GetMapping("/options")
    public Result<List<PrjProject>> options() {
        return Result.success(projectMapper.selectList(
                new LambdaQueryWrapper<PrjProject>().eq(PrjProject::getStatus, 0)
                        .select(PrjProject::getId, PrjProject::getName)));
    }

    @OperLog(module = "项目管理", action = "创建项目")
    @PostMapping
    public Result<PrjProject> create(@RequestBody PrjProject project) {
        String me = currentUsername();
        project.setOwnerUsername(me);
        if (project.getStatus() == null) project.setStatus(0);
        projectMapper.insert(project);
        workflowEngine.initDefault(project.getId());
        PrjMember owner = new PrjMember();
        owner.setProjectId(project.getId());
        owner.setUsername(me);
        owner.setRoleKey("OWNER");
        memberMapper.insert(owner);
        return Result.success(project);
    }

    @OperLog(module = "项目管理", action = "更新项目")
    @PutMapping("/{id}")
    public Result<String> update(@PathVariable Long id, @RequestBody PrjProject project) {
        project.setId(id);
        projectMapper.updateById(project);
        return Result.success("ok");
    }

    @OperLog(module = "项目管理", action = "删除项目")
    @ProjectScoped(value = ProjectScoped.ObjType.PROJECT, ownerOnly = true)
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        projectMapper.deleteById(id);
        return Result.success("ok");
    }

    // ---------- 成员 ----------
    @GetMapping("/{id}/members")
    public Result<List<PrjMember>> members(@PathVariable Long id) {
        return Result.success(memberMapper.selectList(
                new LambdaQueryWrapper<PrjMember>().eq(PrjMember::getProjectId, id)));
    }

    @OperLog(module = "项目管理", action = "添加成员")
    @ProjectScoped(value = ProjectScoped.ObjType.PROJECT, ownerOnly = true)
    @PostMapping("/{id}/members")
    public Result<String> addMember(@PathVariable Long id, @RequestBody PrjMember member) {
        member.setProjectId(id);
        Long exists = memberMapper.selectCount(new LambdaQueryWrapper<PrjMember>()
                .eq(PrjMember::getProjectId, id).eq(PrjMember::getUsername, member.getUsername()));
        if (exists > 0) return Result.fail(400, "该成员已存在");
        memberMapper.insert(member);
        return Result.success("ok");
    }

    @OperLog(module = "项目管理", action = "移除成员")
    @ProjectScoped(value = ProjectScoped.ObjType.PROJECT, ownerOnly = true)
    @DeleteMapping("/{id}/members/{username}")
    public Result<String> removeMember(@PathVariable Long id, @PathVariable String username) {
        memberMapper.delete(new LambdaQueryWrapper<PrjMember>()
                .eq(PrjMember::getProjectId, id).eq(PrjMember::getUsername, username));
        return Result.success("ok");
    }

    // ---------- 迭代 ----------
    @GetMapping("/{id}/iterations")
    public Result<List<PrjIteration>> iterations(@PathVariable Long id) {
        return Result.success(iterationMapper.selectList(
                new LambdaQueryWrapper<PrjIteration>()
                        .eq(PrjIteration::getProjectId, id).orderByDesc(PrjIteration::getId)));
    }

    @OperLog(module = "项目管理", action = "创建迭代")
    @PostMapping("/{id}/iterations")
    public Result<PrjIteration> createIteration(@PathVariable Long id, @RequestBody PrjIteration it) {
        it.setProjectId(id);
        if (it.getStatus() == null) it.setStatus(0);
        iterationMapper.insert(it);
        return Result.success(it);
    }

    @OperLog(module = "项目管理", action = "删除迭代")
    @DeleteMapping("/iterations/{itId}")
    public Result<String> deleteIteration(@PathVariable Long itId) {
        iterationMapper.deleteById(itId);
        return Result.success("ok");
    }

    /**
     * 关闭迭代（方案 P0-3②）：置为已关闭；非终态任务的归属清空（移回 backlog）并记动态；
     * 通知项目全部成员（iteration 类型）。
     */
    @OperLog(module = "项目管理", action = "关闭迭代")
    @ProjectScoped(value = ProjectScoped.ObjType.ITERATION, ownerOnly = true)
    @PostMapping("/iterations/{itId}/close")
    public Result<String> closeIteration(@PathVariable Long itId) {
        PrjIteration it = iterationMapper.selectById(itId);
        if (it == null) return Result.fail(404, "迭代不存在");
        if (Integer.valueOf(1).equals(it.getStatus())) return Result.fail(400, "迭代已关闭");

        // 项目工作流的终态集合（category=DONE），非终态任务移回 backlog
        Set<String> doneStates = workflowEngine.statesOf(it.getProjectId()).stream()
                .filter(s -> "DONE".equals(s.getCategory()))
                .map(WorkflowState::getName).collect(Collectors.toSet());
        List<PrjTask> unfinished = taskMapper.selectList(new LambdaQueryWrapper<PrjTask>()
                .eq(PrjTask::getIterationId, itId));
        int moved = 0;
        for (PrjTask t : unfinished) {
            if (doneStates.contains(t.getStatus())) continue;
            // updateById 忽略 null 字段，清空归属需显式 set null
            taskMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PrjTask>()
                    .eq(PrjTask::getId, t.getId())
                    .set(PrjTask::getIterationId, null));
            collab.record("TASK", t.getId(), "field", "iteration", it.getName(), "backlog", null);
            moved++;
        }

        PrjIteration patch = new PrjIteration();
        patch.setId(itId);
        patch.setStatus(1);
        iterationMapper.updateById(patch);

        for (PrjMember m : memberMapper.selectList(
                new LambdaQueryWrapper<PrjMember>().eq(PrjMember::getProjectId, it.getProjectId()))) {
            notify.push(m.getUsername(), "iteration", "迭代已关闭：" + it.getName(),
                    moved + " 个未完成任务已移回 backlog", "/#/project");
        }
        return Result.success("已关闭，" + moved + " 个未完成任务移回 backlog");
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
