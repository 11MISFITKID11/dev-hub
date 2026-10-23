package com.devhub.module.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devhub.module.project.entity.PrjRequirement;
import com.devhub.module.project.entity.WorkflowState;
import com.devhub.module.project.mapper.PrjRequirementMapper;
import com.devhub.module.task.entity.PrjTask;
import com.devhub.module.task.mapper.PrjTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 需求状态联动服务（从 RequirementController 抽出，供 TaskService 事务内调用）
 * <p>
 * Story 下任务全部到达项目工作流终态 → DONE；存在非终态任务 → DEVELOPING
 * （含任务回退后从 DONE 回归）。REJECTED 不被联动覆盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementStatusService {

    private final PrjRequirementMapper reqMapper;
    private final PrjTaskMapper taskMapper;
    private final WorkflowEngine workflowEngine;

    /** 联动 Story 状态；失败静默（不拖垮外层任务事务） */
    public void syncStoryStatus(Long requirementId) {
        if (requirementId == null) return;
        try {
            PrjRequirement story = reqMapper.selectById(requirementId);
            if (story == null || !"STORY".equals(story.getRtype()) || "REJECTED".equals(story.getStatus())) return;
            List<PrjTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<PrjTask>()
                    .eq(PrjTask::getRequirementId, requirementId));
            if (tasks.isEmpty()) return;
            Set<String> doneStates = workflowEngine.statesOf(story.getProjectId()).stream()
                    .filter(s -> "DONE".equals(s.getCategory()))
                    .map(WorkflowState::getName)
                    .collect(Collectors.toSet());
            boolean allDone = tasks.stream().allMatch(t -> doneStates.contains(t.getStatus()));
            String target = allDone ? "DONE" : "DEVELOPING";
            if (!target.equals(story.getStatus())) {
                PrjRequirement patch = new PrjRequirement();
                patch.setId(requirementId);
                patch.setStatus(target);
                patch.setVersion(story.getVersion());   // 乐观锁：并发联动冲突则本次放弃
                if (reqMapper.updateById(patch) == 0) {
                    log.debug("需求状态联动冲突（已并发变更，放弃本次联动）: requirementId={}", requirementId);
                }
            }
        } catch (Exception e) {
            log.warn("需求状态联动失败（不影响任务主流程）: requirementId={}, cause={}", requirementId, e.getMessage());
        }
    }
}
