package com.devhub.module.ai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识反馈实体（错误知识停用的数据来源）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_feedback")
public class KnowledgeFeedback {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String documentId;

    /** 1=有用 0=无用 */
    private Integer useful;

    private LocalDateTime createdAt;
}
