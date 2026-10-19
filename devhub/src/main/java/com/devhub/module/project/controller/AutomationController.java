package com.devhub.module.project.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devhub.common.OperLog;
import com.devhub.common.ProjectScoped;
import com.devhub.module.ai.common.Result;
import com.devhub.module.project.entity.PrjAutomationRule;
import com.devhub.module.project.mapper.PrjAutomationRuleMapper;
import com.devhub.module.project.service.AutomationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 项目自动化规则管理（P2）：触发器/动作目录 + 项目规则 CRUD + 启停。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/automation")
@RequiredArgsConstructor
public class AutomationController {

    private final PrjAutomationRuleMapper ruleMapper;

    /** 触发器与动作可选目录（前端下拉用） */
    public static final List<String> TRIGGERS = List.of(
            AutomationService.TRIGGER_TASK_CREATED,
            AutomationService.TRIGGER_STATUS_CHANGED,
            AutomationService.TRIGGER_ASSIGNEE_CHANGED);
    public static final List<String> ACTIONS = List.of("NOTIFY", "AUTO_ASSIGN", "SET_PRIORITY");

    @GetMapping
    public Result<List<PrjAutomationRule>> list(@PathVariable Long projectId) {
        return Result.success(ruleMapper.selectList(new LambdaQueryWrapper<PrjAutomationRule>()
                .eq(PrjAutomationRule::getProjectId, projectId)
                .orderByDesc(PrjAutomationRule::getId)));
    }

    @OperLog(module = "项目管理", action = "新增自动化规则")
    @ProjectScoped(ProjectScoped.ObjType.PROJECT)
    @PostMapping
    public Result<PrjAutomationRule> create(@PathVariable Long projectId, @RequestBody PrjAutomationRule rule) {
        if (rule.getName() == null || rule.getName().isBlank()) return Result.fail(400, "规则名不能为空");
        if (!TRIGGERS.contains(rule.getTriggerType())) return Result.fail(400, "非法触发器");
        if (!ACTIONS.contains(rule.getActionType())) return Result.fail(400, "非法动作");
        if (rule.getTriggerValue() == null || rule.getTriggerValue().isBlank()) rule.setTriggerValue("*");
        if (("AUTO_ASSIGN".equals(rule.getActionType()) || "SET_PRIORITY".equals(rule.getActionType()))
                && (rule.getActionValue() == null || rule.getActionValue().isBlank())) {
            return Result.fail(400, "该动作必须配置动作值（用户名 / 优先级）");
        }
        rule.setProjectId(projectId);
        if (rule.getEnabled() == null) rule.setEnabled(1);
        ruleMapper.insert(rule);
        return Result.success(rule);
    }

    @OperLog(module = "项目管理", action = "启停自动化规则")
    @ProjectScoped(ProjectScoped.ObjType.PROJECT)
    @PutMapping("/{id}/toggle")
    public Result<String> toggle(@PathVariable Long projectId, @PathVariable Long id) {
        PrjAutomationRule r = ruleMapper.selectById(id);
        if (r == null || !r.getProjectId().equals(projectId)) return Result.fail(404, "规则不存在");
        PrjAutomationRule patch = new PrjAutomationRule();
        patch.setId(id);
        patch.setEnabled(r.getEnabled() != null && r.getEnabled() == 1 ? 0 : 1);
        ruleMapper.updateById(patch);
        return Result.success("ok");
    }

    @OperLog(module = "项目管理", action = "删除自动化规则")
    @ProjectScoped(ProjectScoped.ObjType.PROJECT)
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long projectId, @PathVariable Long id) {
        ruleMapper.deleteById(id);
        return Result.success("ok");
    }
}
