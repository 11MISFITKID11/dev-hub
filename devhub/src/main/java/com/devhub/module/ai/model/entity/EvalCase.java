package com.devhub.module.ai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("eval_case")
public class EvalCase {
    @TableId(type = IdType.AUTO)
    Long id;
    String question;
    String expectDocName;
    String expectKeywords;
    Integer enabled;
    LocalDateTime createTime;
}
