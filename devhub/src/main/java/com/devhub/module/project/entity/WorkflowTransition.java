package com.devhub.module.project.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("prj_workflow_transition")
public class WorkflowTransition {
    @TableId(type = IdType.AUTO)
    Long id;
    Long projectId;
    String fromState;
    String toState;
}
