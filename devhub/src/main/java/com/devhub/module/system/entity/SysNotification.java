package com.devhub.module.system.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sys_notification")
public class SysNotification {
    @TableId(type = IdType.AUTO)
    Long id;
    String recipient;
    String type;
    String title;
    String content;
    String refUrl;
    Integer isRead;
    LocalDateTime createTime;
}
