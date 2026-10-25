package com.devhub.module.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工单往来回复（客户补充 / 坐席处理记录与结单说明） */
@Data
@TableName("support_ticket_reply")
public class SupportTicketReply {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ticketId;
    private String author;
    /** customer / agent */
    private String role;
    private String content;
    private LocalDateTime createTime;
}
