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
    /** MRR：首个命中文档排名倒数（未命中=0，未设期望=NULL） */
    Double mrr;
    /** NDCG@k：二值相关度折损累计增益（rank1=1.0，未设期望=NULL） */
    Double ndcg;
    String topDocs;
    Double faithScore;
    String answerExcerpt;
}
