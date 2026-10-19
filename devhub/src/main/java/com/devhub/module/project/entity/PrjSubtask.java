package com.devhub.module.project.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("prj_subtask")
public class PrjSubtask {
    @TableId(type = IdType.AUTO)
    Long id;
    Long taskId;
    String title;
    Integer done;
    Integer sort;

    @TableLogic
    Integer deleted;
    LocalDateTime createTime;
}
