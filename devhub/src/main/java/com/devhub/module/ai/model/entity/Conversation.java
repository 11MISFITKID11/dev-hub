package com.devhub.module.ai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 会话标题，由首轮对话自动生成 */
    private String title;

    /** 用户标识 */
    private String userId;

    /** 消息轮次数 */
    private Integer turnCount;

    /** 人工接管状态：PENDING 待接入 / ACTIVE 接管中 / NULL=AI 模式 */
    private String takeoverStatus;

    /** 接管坐席用户名 */
    private String handler;

    /** 转人工联动的工单 ID */
    private Long takeoverTicketId;

    /** 逻辑删除标记 */
    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
