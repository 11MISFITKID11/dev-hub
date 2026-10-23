package com.devhub.module.task.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.devhub.common.OperLog;
import com.devhub.common.ProjectScoped;
import com.devhub.module.ai.common.Result;
import com.devhub.module.task.entity.PrjTask;
import com.devhub.module.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 任务中心：CRUD + 状态机（TODO→DOING→DONE，可回退）+ 我的任务。
 * 业务逻辑在 TaskService（事务 + 乐观锁），本层只做参数绑定与鉴权注解。
 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @GetMapping
    public Result<Page<PrjTask>> list(@RequestParam(defaultValue = "1") long page,
                                      @RequestParam(defaultValue = "10") long size,
                                      @RequestParam(required = false) Long projectId,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) Boolean mine,
                                      @RequestParam(required = false) String keyword) {
        return Result.success(taskService.list(page, size, projectId, status, mine, keyword, currentUsername()));
    }

    @OperLog(module = "任务中心", action = "创建任务")
    @PostMapping
    public Result<PrjTask> create(@RequestBody PrjTask task) {
        return Result.success(taskService.create(task, currentUsername()));
    }

    @OperLog(module = "任务中心", action = "更新任务")
    @ProjectScoped(ProjectScoped.ObjType.TASK)
    @PutMapping("/{id}")
    public Result<String> update(@PathVariable Long id, @RequestBody PrjTask task) {
        taskService.update(id, task);
        return Result.success("ok");
    }

    @OperLog(module = "任务中心", action = "删除任务")
    @ProjectScoped(ProjectScoped.ObjType.TASK)
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        taskService.delete(id);
        return Result.success("ok");
    }

    /** 看板：按状态分组（列随项目工作流动态定义） */
    @GetMapping("/board")
    public Result<Map<String, List<PrjTask>>> board(@RequestParam(required = false) Long projectId) {
        return Result.success(taskService.board(projectId));
    }

    /** 状态推进（元数据驱动：目标状态由项目工作流规则决定 + WIP 拦截 + 并发冲突 409） */
    @OperLog(module = "任务中心", action = "推进状态")
    @ProjectScoped(ProjectScoped.ObjType.TASK)
    @PostMapping("/{id}/advance")
    public Result<String> advance(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean forward) {
        return Result.success(taskService.advance(id, forward));
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
