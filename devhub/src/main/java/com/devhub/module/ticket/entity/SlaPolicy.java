package com.devhub.module.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** SLA 策略：按优先级定义首响/解决双时钟 */
@Data
@TableName("sla_policy")
public class SlaPolicy {
    @TableId(type = IdType.INPUT)
    private String priority;
    private Integer firstResponseMinutes;
    private Integer resolveMinutes;
    private String description;
}
