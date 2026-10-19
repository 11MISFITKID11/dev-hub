package com.devhub.module.project.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("prj_requirement")
public class PrjRequirement {
    @TableId(type = IdType.AUTO)
    Long id;
    Long projectId;
    Long parentId;
    String rtype;
    String title;
    String description;
    String status;
    String priority;
    Integer storyPoints;
    String ownerUsername;
    Long iterationId;

    @TableLogic
    Integer deleted;
    LocalDateTime createTime;
    LocalDateTime updateTime;
}
