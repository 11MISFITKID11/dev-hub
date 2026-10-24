package com.devhub.module.ai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("eval_run")
public class EvalRun {
    @TableId(type = IdType.AUTO)
    Long id;
    String mode;
    Integer total;
    Integer hitCount;
    Integer gatePass;
    Double avgTop1;
    Double avgFaith;
    Long costMs;
    String createdBy;
    LocalDateTime createTime;
}
