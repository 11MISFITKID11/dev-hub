package com.devhub.module.defect.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("prj_defect")
public class PrjDefect {
    @TableId(type = IdType.AUTO)
    Long id;

    Long projectId;
    Long taskId;
    String title;
    String description;
    String severity;
    String status;
    String reporterUsername;
    String assigneeUsername;

    @TableLogic
    Integer deleted;
    LocalDateTime createTime;
    LocalDateTime updateTime;
}
