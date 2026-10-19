package com.devhub.module.project.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("prj_workflow_state")
public class WorkflowState {
    @TableId(type = IdType.AUTO)
    Long id;
    Long projectId;
    String name;
    String category;
    Integer sort;
    Integer isDefaultStart;
    Integer wipLimit;
    @TableLogic
    Integer deleted;
}
