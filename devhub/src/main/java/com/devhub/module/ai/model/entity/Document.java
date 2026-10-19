package com.devhub.module.ai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("document")
public class Document {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 文件名 */
    private String fileName;

    /** 文件类型（pdf, docx, txt 等） */
    private String fileType;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 文件存储路径 */
    private String filePath;

    /** 切片数量 */
    private Integer chunkCount;

    /** 处理状态：pending / processing / completed / failed */
    private String status;

    /** 生命周期状态：active / draft / disabled / retired / superseded / conflict */
    private String lifeStatus;

    /** 来源：upload / ai_generated */
    private String sourceType;

    /** 知识过期时间（到期后检索不再返回） */
    private java.time.LocalDateTime expireAt;

    /** 版本号（同名文档自动递增） */
    private Integer version;

    /** 被替代文档ID（版本链） */
    private String supersededBy;

    /** 检索命中次数 */
    private Integer hitCount;

    /** AI生成时的来源会话 */
    private String sourceConversationId;

    /** 文档中出现的URL（JSON数组，探活用） */
    private String urls;

    /** 文档中心分类 */
    private Long categoryId;
    /** 上传人（登录名） */
    private String uploader;
    /** 审核状态：PENDING/PUBLISHED/REJECTED（治理位，检索可见性由 lifeStatus 管理） */
    private String auditStatus;

    /** 部门数据域（NULL=公共可见） */
    private Long deptId;

    /** 上传用户 */
    private String userId;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
