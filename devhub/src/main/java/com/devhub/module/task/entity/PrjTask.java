package com.devhub.module.task.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("prj_task")
public class PrjTask {
    @TableId(type = IdType.AUTO)
    Long id;

    Long projectId;
    Long iterationId;
    Long requirementId;
    String title;
    String content;
    String status;
    String priority;
    String assigneeUsername;
    String creatorUsername;
    LocalDate dueDate;
    java.time.LocalDateTime startAt;
    java.time.LocalDateTime doneAt;

    @TableLogic
    Integer deleted;
    LocalDateTime createTime;
    LocalDateTime updateTime;
}
