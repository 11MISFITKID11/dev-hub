package com.devhub.module.ai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档切片实体
 * <p>
 * 切片内容同步落 MySQL，作为混合检索的关键词通道（ngram 全文索引）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("document_chunk")
public class DocumentChunkEntity {

    @TableId
    private String id;

    private String documentId;

    private String fileName;

    private String content;

    private Integer chunkIndex;

    /** 所属文档生命周期状态（检索过滤用，冗余自 document.life_status） */
    private String docStatus;

    /** Parent-Child：子块指向父块 id；父块/旧数据为 null */
    private String parentId;

    /** 0=子块 1=父块 NULL=旧版单层切片 */
    private Integer chunkType;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 全文检索相关度（仅查询时回填，非表字段） */
    @TableField(exist = false)
    private Double score;
}
