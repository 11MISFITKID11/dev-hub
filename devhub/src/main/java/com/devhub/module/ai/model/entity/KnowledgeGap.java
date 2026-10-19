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
 * 知识缺口实体（拒答记录，自动知识生产的触发源）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_gap")
public class KnowledgeGap {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String questionHash;

    private String question;

    private String conversationId;

    /** open / generating / resolved / dismissed */
    private String status;

    private Integer hitCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
