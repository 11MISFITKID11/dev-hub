package com.devhub.module.project.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("prj_task_dep")
public class PrjTaskDep {
    @TableId(type = IdType.AUTO)
    Long id;
    Long taskId;
    Long dependsOn;

    @TableLogic
    Integer deleted;
    LocalDateTime createTime;
}
