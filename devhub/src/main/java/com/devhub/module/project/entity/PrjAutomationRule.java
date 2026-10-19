package com.devhub.module.project.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("prj_automation_rule")
public class PrjAutomationRule {
    @TableId(type = IdType.AUTO)
    Long id;
    Long projectId;
    String name;
    String triggerType;
    String triggerValue;
    String actionType;
    String actionValue;
    Integer enabled;

    @TableLogic
    Integer deleted;
    LocalDateTime createTime;
}
