package com.devhub.module.project.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("prj_worklog")
public class PrjWorklog {
    @TableId(type = IdType.AUTO)
    Long id;
    Long taskId;
    String username;
    BigDecimal hours;
    LocalDate workDate;
    String note;

    @TableLogic
    Integer deleted;
    LocalDateTime createTime;
}
