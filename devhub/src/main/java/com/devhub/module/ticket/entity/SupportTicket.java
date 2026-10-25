package com.devhub.module.ticket.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客服工单（AI 客服 + 人工工单双轨闭环的人工侧）
 * <p>状态机 PENDING → PROCESSING → RESOLVED → CLOSED，流转全部走 CAS 条件更新（见 TicketService）；
 * 乐观锁 @Version 防并发改单。
 */
@Data
@TableName("support_ticket")
public class SupportTicket {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ticketNo;
    private String username;
    private String title;
    private String content;
    /** manual / chat_refusal / chat_dislike */
    private String source;
    private String conversationId;
    private String messageId;
    /** PRODUCT / BUG / ACCOUNT / BILLING / USAGE / OTHER */
    private String category;
    /** HIGH / MEDIUM / LOW */
    private String priority;
    private Integer aiClassified;
    /** PENDING / PROCESSING / RESOLVED / CLOSED */
    private String status;
    private String handler;
    private Integer satisfaction;
    private String satisfactionComment;
    private Integer qcScore;
    private String qcResult;

    @Version
    private Integer version;
    @TableLogic
    private Integer deleted;
    /** SLA 首响截止 */
    private LocalDateTime slaFirstDue;
    /** SLA 解决截止（不含暂停顺延，生效值见 SlaClock.effectiveResolveDue） */
    private LocalDateTime slaResolveDue;
    /** 实际首响时间（坐席首次回复） */
    private LocalDateTime slaFirstAt;
    /** 是否已发生 SLA 超时（超时升级后置 1，防重复升级） */
    private Integer slaBreach;
    /** 等待客户回复累计暂停毫秒 */
    private Long slaPauseMs;
    /** 当前暂停起点（NULL=计时中） */
    private LocalDateTime slaPausedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
