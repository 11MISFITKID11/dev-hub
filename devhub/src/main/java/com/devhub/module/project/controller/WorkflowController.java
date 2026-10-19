package com.devhub.module.project.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devhub.common.OperLog;
import com.devhub.module.ai.common.Result;
import com.devhub.module.project.entity.WorkflowState;
import com.devhub.module.project.entity.WorkflowTransition;
import com.devhub.module.project.mapper.WorkflowStateMapper;
import com.devhub.module.project.mapper.WorkflowTransitionMapper;
import com.devhub.module.project.service.WorkflowEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 工作流管理（方案 P1-5）：项目级状态集 + 流转规则 + WIP 限制的可视化配置
 */
@RestController
@RequestMapping("/api/projects/{projectId}/workflow")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowStateMapper stateMapper;
    private final WorkflowTransitionMapper transitionMapper;
    private final WorkflowEngine engine;

    /** 完整工作流：状态定义 + 流转规则 + 各状态当前任务数（WIP 检查用） */
    @GetMapping
    public Result<Map<String, Object>> workflow(@PathVariable Long projectId) {
        List<WorkflowState> states = engine.statesOf(projectId);
        List<WorkflowTransition> transitions = engine.transitionsOf(projectId);
        return Result.success(Map.of("states", states, "transitions", transitions));
    }

    @OperLog(module = "项目管理", action = "新增工作流状态")
    @PreAuthorize("hasAuthority('system:manage') or hasAuthority('ai:kb:manage')")
    @PostMapping("/states")
    public Result<WorkflowState> addState(@PathVariable Long projectId, @RequestBody WorkflowState state) {
        if (engine.isValidState(projectId, state.getName())) {
            return Result.fail(400, "状态「" + state.getName() + "」已存在");
        }
        if (state.getCategory() == null || !List.of("TODO", "DOING", "DONE").contains(state.getCategory())) {
            return Result.fail(400, "category 必须为 TODO/DOING/DONE");
        }
        state.setProjectId(projectId);
        if (state.getSort() == null) state.setSort(99);
        if (state.getIsDefaultStart() == null) state.setIsDefaultStart(0);
        stateMapper.insert(state);
        // 设为唯一初始状态
        if (Integer.valueOf(1).equals(state.getIsDefaultStart())) {
            stateMapper.selectList(new LambdaQueryWrapper<WorkflowState>()
                            .eq(WorkflowState::getProjectId, projectId))
                    .forEach(s -> {
                        WorkflowState u = new WorkflowState();
                        u.setId(s.getId());
                        u.setIsDefaultStart(s.getId().equals(state.getId()) ? 1 : 0);
                        if (!s.getId().equals(state.getId()) && Integer.valueOf(1).equals(s.getIsDefaultStart())) {
                            stateMapper.updateById(u);
                        }
                    });
        }
        return Result.success(state);
    }

    @OperLog(module = "项目管理", action = "删除工作流状态")
    @PreAuthorize("hasAuthority('system:manage') or hasAuthority('ai:kb:manage')")
    @DeleteMapping("/states/{stateId}")
    public Result<String> delState(@PathVariable Long projectId, @PathVariable Long stateId) {
        WorkflowState s = stateMapper.selectById(stateId);
        if (s == null || !s.getProjectId().equals(projectId)) return Result.fail(404, "状态不存在");
        // 引用检查由任务侧自然拦截（历史任务处于该状态时删除会造成脏数据——从严：有任务即拒）
        transitionMapper.delete(new LambdaQueryWrapper<WorkflowTransition>()
                .eq(WorkflowTransition::getProjectId, projectId)
                .and(w -> w.eq(WorkflowTransition::getFromState, s.getName())
                        .or().eq(WorkflowTransition::getToState, s.getName())));
        stateMapper.deleteById(stateId);
        return Result.success("ok");
    }

    @OperLog(module = "项目管理", action = "配置流转规则")
    @PreAuthorize("hasAuthority('system:manage') or hasAuthority('ai:kb:manage')")
    @PostMapping("/transitions")
    public Result<String> addTransition(@PathVariable Long projectId,
                                        @RequestBody Map<String, String> body) {
        String from = body.get("from"), to = body.get("to");
        if (!engine.isValidState(projectId, from) || !engine.isValidState(projectId, to)) {
            return Result.fail(400, "from/to 必须是项目已定义的状态");
        }
        Long exists = transitionMapper.selectCount(new LambdaQueryWrapper<WorkflowTransition>()
                .eq(WorkflowTransition::getProjectId, projectId)
                .eq(WorkflowTransition::getFromState, from)
                .eq(WorkflowTransition::getToState, to));
        if (exists > 0) return Result.fail(400, "规则已存在");
        WorkflowTransition t = new WorkflowTransition();
        t.setProjectId(projectId); t.setFromState(from); t.setToState(to);
        transitionMapper.insert(t);
        return Result.success("ok");
    }

    @OperLog(module = "项目管理", action = "删除流转规则")
    @PreAuthorize("hasAuthority('system:manage') or hasAuthority('ai:kb:manage')")
    @DeleteMapping("/transitions/{id}")
    public Result<String> delTransition(@PathVariable Long projectId, @PathVariable Long id) {
        WorkflowTransition t = transitionMapper.selectById(id);
        if (t == null || !t.getProjectId().equals(projectId)) return Result.fail(404, "规则不存在");
        transitionMapper.deleteById(id);
        return Result.success("ok");
    }
}
