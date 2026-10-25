package com.devhub.module.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工单标签（VIP/退款/上门等运营标记） */
@Data
@TableName("ticket_tag")
public class TicketTag {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String color;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
