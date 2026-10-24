package com.devhub.module.ai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("eval_result")
public class EvalResult {
    @TableId(type = IdType.AUTO)
    Long id;
    Long runId;
    Long caseId;
    String question;
    Integer hit;
    Integer gatePass;
    Double top1Score;
    String topDocs;
    Double faithScore;
    String answerExcerpt;
}
