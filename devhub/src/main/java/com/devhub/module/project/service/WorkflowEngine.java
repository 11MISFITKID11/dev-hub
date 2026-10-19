package com.devhub.module.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devhub.module.project.entity.WorkflowState;
import com.devhub.module.project.entity.WorkflowTransition;
import com.devhub.module.project.mapper.WorkflowStateMapper;
import com.devhub.module.project.mapper.WorkflowTransitionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工作流引擎（方案 P1-5，元数据驱动设计）
 * <p>
 * 任务状态机不再硬编码 TODO/DOING/DONE，而是按项目查 prj_workflow_state 定义 +
 * prj_workflow_transition 流转规则。新项目自动套用默认模板（project_id=0 的种子）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowEngine {

    private final WorkflowStateMapper stateMapper;
    private final WorkflowTransitionMapper transitionMapper;

    /** 项目的全部状态（按 sort） */
    public List<WorkflowState> statesOf(Long projectId) {
        return stateMapper.selectList(new LambdaQueryWrapper<WorkflowState>()
                .eq(WorkflowState::getProjectId, projectId)
                .orderByAsc(WorkflowState::getSort));
    }

    /** 项目的全部流转规则 */
    public List<WorkflowTransition> transitionsOf(Long projectId) {
        return transitionMapper.selectList(new LambdaQueryWrapper<WorkflowTransition>()
                .eq(WorkflowTransition::getProjectId, projectId));
    }

    /** 新任务初始状态 */
    public String defaultStartState(Long projectId) {
        WorkflowState s = stateMapper.selectOne(new LambdaQueryWrapper<WorkflowState>()
                .eq(WorkflowState::getProjectId, projectId)
                .eq(WorkflowState::getIsDefaultStart, 1)
                .last("LIMIT 1"));
        return s != null ? s.getName() : "TODO";
    }

    /** 状态是否属于该项目的合法定义 */
    public boolean isValidState(Long projectId, String state) {
        return stateMapper.selectCount(new LambdaQueryWrapper<WorkflowState>()
                .eq(WorkflowState::getProjectId, projectId)
                .eq(WorkflowState::getName, state)) > 0;
    }

    /** 状态值校验（PUT 直改场景，含"未传状态=放行"） */
    public String validateState(Long projectId, String state) {
        if (state == null || state.isBlank()) return null;
        if (!isValidState(projectId, state)) {
            return "非法状态「" + state + "」，项目允许：" + stateNames(projectId);
        }
        return null;
    }

    /** 流转校验 */
    public String validateTransition(Long projectId, String from, String to) {
        Long allowed = transitionMapper.selectCount(new LambdaQueryWrapper<WorkflowTransition>()
                .eq(WorkflowTransition::getProjectId, projectId)
                .eq(WorkflowTransition::getFromState, from)
                .eq(WorkflowTransition::getToState, to));
        if (allowed == 0) {
            return "状态「" + from + "」不允许流转到「" + to + "」（项目工作流未定义该路径）";
        }
        return null;
    }

    /** 下一个可流转状态（advance：取 sort 严格大于当前且最近的一个，逐级前进不跳级；无则任选一条） */
    public String nextForwardState(Long projectId, String from) {
        List<WorkflowTransition> outs = transitionMapper.selectList(
                new LambdaQueryWrapper<WorkflowTransition>()
                        .eq(WorkflowTransition::getProjectId, projectId)
                        .eq(WorkflowTransition::getFromState, from));
        if (outs.isEmpty()) return null;
        List<WorkflowState> ordered = statesOf(projectId);
        int fromSort = sortOf(ordered, from);
        String best = null; int bestSort = Integer.MAX_VALUE;
        for (WorkflowTransition t : outs) {
            int toSort = sortOf(ordered, t.getToState());
            if (toSort > fromSort && toSort < bestSort) {
                best = t.getToState(); bestSort = toSort;
            }
        }
        return best != null ? best : outs.get(0).getToState();
    }

    /** 上一个可流转状态（back：目标 sort 严格小于当前，取最大者） */
    public String nextBackState(Long projectId, String from) {
        List<WorkflowTransition> outs = transitionMapper.selectList(
                new LambdaQueryWrapper<WorkflowTransition>()
                        .eq(WorkflowTransition::getProjectId, projectId)
                        .eq(WorkflowTransition::getFromState, from));
        if (outs.isEmpty()) return null;
        List<WorkflowState> ordered = statesOf(projectId);
        int fromSort = sortOf(ordered, from);
        String best = null; int bestSort = Integer.MIN_VALUE;
        for (WorkflowTransition t : outs) {
            int toSort = sortOf(ordered, t.getToState());
            if (toSort < fromSort && toSort > bestSort) { best = t.getToState(); bestSort = toSort; }
        }
        return best != null ? best : outs.get(0).getToState();
    }

    private int sortOf(List<WorkflowState> states, String name) {
        return states.stream().filter(s -> s.getName().equals(name))
                .findFirst().map(s -> s.getSort() == null ? 999 : s.getSort()).orElse(999);
    }

    public List<String> stateNames(Long projectId) {
        return statesOf(projectId).stream().map(WorkflowState::getName).collect(Collectors.toList());
    }

    /** 新项目初始化：从默认模板（project_id=0）复制状态与规则 */
    public void initDefault(Long projectId) {
        for (WorkflowState s : statesOf(0L)) {
            WorkflowState copy = new WorkflowState();
            copy.setProjectId(projectId); copy.setName(s.getName()); copy.setCategory(s.getCategory());
            copy.setSort(s.getSort()); copy.setIsDefaultStart(s.getIsDefaultStart()); copy.setWipLimit(s.getWipLimit());
            stateMapper.insert(copy);
        }
        for (WorkflowTransition t : transitionsOf(0L)) {
            WorkflowTransition copy = new WorkflowTransition();
            copy.setProjectId(projectId); copy.setFromState(t.getFromState()); copy.setToState(t.getToState());
            transitionMapper.insert(copy);
        }
        log.info("项目 {} 已初始化默认工作流", projectId);
    }
}
