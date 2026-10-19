package com.devhub.module.project.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("prj_project")
public class PrjProject {
    @TableId(type = IdType.AUTO)
    Long id;

    String name;
    String code;
    String ownerUsername;
    Long deptId;
    Integer status;
    LocalDate startDate;
    LocalDate endDate;
    String description;

    @TableLogic
    Integer deleted;
    LocalDateTime createTime;
    LocalDateTime updateTime;
}
