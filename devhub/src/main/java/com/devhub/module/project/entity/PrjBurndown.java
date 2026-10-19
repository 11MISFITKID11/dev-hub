package com.devhub.module.project.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;

@Data
@TableName("prj_burndown")
public class PrjBurndown {
    @TableId(type = IdType.AUTO)
    Long id;
    Long iterationId;
    LocalDate snapshotDate;
    Integer totalTasks;
    Integer openTasks;
    Integer doneTasks;
}
