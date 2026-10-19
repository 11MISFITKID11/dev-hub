package com.devhub.module.project.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("prj_iteration")
public class PrjIteration {
    @TableId(type = IdType.AUTO)
    Long id;

    Long projectId;
    String name;
    LocalDate startDate;
    LocalDate endDate;
    Integer status;

    @TableLogic
    Integer deleted;
}
