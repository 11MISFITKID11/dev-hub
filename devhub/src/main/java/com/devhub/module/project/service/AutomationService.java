package com.devhub.module.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devhub.common.NotificationService;
import com.devhub.module.project.entity.PrjAutomationRule;
import com.devhub.module.project.mapper.PrjAutomationRuleMapper;
import com.devhub.module.task.entity.PrjTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自动化规则引擎（P2，Jira Automation lite）：
 * 任务事件（创建/状态变更/指派变更）→ 项目规则匹配 → 动作执行（站内通知/自动指派/设优先级）。
 * 规则执行失败只记日志，绝不影响业务主流程。通知渠道仅站内信（IM Webhook 按范围决策不做）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationService {

    public static final String TRIGGER_TASK_CREATED = "TASK_CREATED";
    public static final String TRIGGER_STATUS_CHANGED = "STATUS_CHANGED";
    public static final String TRIGGER_ASSIGNEE_CHANGED = "ASSIGNEE_CHANGED";

    public static final Set<String> VALID_TRIGGERS = Set.of(TRIGGER_TASK_CREATED, TRIGGER_STATUS_CHANGED, TRIGGER_ASSIGNEE_CHANGED);
    public static final Set<String> VALID_ACTIONS = Set.of("NOTIFY", "AUTO_ASSIGN", "SET_PRIORITY");

    private final PrjAutomationRuleMapper ruleMapper;
    private final NotificationService notify;

    /** 任务创建后触发。@return 是否修改了任务字段（调用方需回写） */
    public boolean onTaskCreated(PrjTask task) {
        return execute(task, TRIGGER_TASK_CREATED, null);
    }

    /** 状态变更后触发（newStatus 供规则匹配具体状态）。@return 是否修改了任务字段 */
    public boolean onStatusChanged(PrjTask task, String newStatus) {
        return execute(task, TRIGGER_STATUS_CHANGED, newStatus);
    }

    /** 指派变更后触发。@return 是否修改了任务字段 */
    public boolean onAssigneeChanged(PrjTask task) {
        return execute(task, TRIGGER_ASSIGNEE_CHANGED, null);
    }

    private boolean execute(PrjTask task, String trigger, String statusValue) {
        boolean mutated = false;
        try {
            List<PrjAutomationRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<PrjAutomationRule>()
                    .eq(PrjAutomationRule::getProjectId, task.getProjectId())
                    .eq(PrjAutomationRule::getEnabled, 1)
                    .eq(PrjAutomationRule::getTriggerType, trigger));
            for (PrjAutomationRule r : rules) {
                if (statusValue != null && !"*".equals(r.getTriggerValue())
                        && !statusValue.equals(r.getTriggerValue())) {
                    continue; // 规则限定了具体状态，不匹配
                }
                mutated |= apply(task, r);
            }
        } catch (Exception e) {
            log.warn("自动化规则执行失败（不影响业务）: projectId={}, trigger={}, err={}",
                    task.getProjectId(), trigger, e.getMessage());
        }
        return mutated;
    }

    private boolean apply(PrjTask task, PrjAutomationRule r) {
        boolean mutated = false;
        switch (r.getActionType()) {
            case "NOTIFY" -> {
                String who = resolveRecipient(task, r.getActionValue());
                if (who != null) {
                    notify.push(who, "assign",
                            "自动化：" + r.getName(),
                            descOf(task, r.getTriggerType()), "/#/task");
                }
            }
            case "AUTO_ASSIGN" -> {
                if (r.getActionValue() != null && !r.getActionValue().isBlank()) {
                    task.setAssigneeUsername(r.getActionValue());
                    log.info("自动化指派：任务#{} → {}（规则:{}）", task.getId(), r.getActionValue(), r.getName());
                    mutated = true;
                }
            }
            case "SET_PRIORITY" -> {
                if (r.getActionValue() != null && !r.getActionValue().isBlank()) {
                    task.setPriority(r.getActionValue());
                    log.info("自动化优先级：任务#{} → {}（规则:{}）", task.getId(), r.getActionValue(), r.getName());
                    mutated = true;
                }
            }
            default -> { }
        }
        return mutated;
    }

    private String resolveRecipient(PrjTask task, String actionValue) {
        if (actionValue == null) return null;
        return switch (actionValue) {
            case "OWNER" -> null; // 任务无 OWNER 概念，退化为创建人
            case "CREATOR", "REPORTER" -> task.getCreatorUsername();
            case "ASSIGNEE" -> task.getAssigneeUsername();
            default -> actionValue; // 直接是用户名
        };
    }

    private String descOf(PrjTask t, String trigger) {
        return switch (trigger) {
            case TRIGGER_TASK_CREATED -> "新任务「" + t.getTitle() + "」已创建";
            case TRIGGER_STATUS_CHANGED -> "任务「" + t.getTitle() + "」状态已变更为 " + t.getStatus();
            case TRIGGER_ASSIGNEE_CHANGED -> "任务「" + t.getTitle() + "」已指派给 " + t.getAssigneeUsername();
            default -> "任务「" + t.getTitle() + "」有更新";
        };
    }

    /** 供配置端点校验 */
    public static Map<String, List<String>> catalog() {
        return Map.of(
                "triggers", List.of(TRIGGER_TASK_CREATED, TRIGGER_STATUS_CHANGED, TRIGGER_ASSIGNEE_CHANGED),
                "actions", List.of("NOTIFY", "AUTO_ASSIGN", "SET_PRIORITY"));
    }
}
