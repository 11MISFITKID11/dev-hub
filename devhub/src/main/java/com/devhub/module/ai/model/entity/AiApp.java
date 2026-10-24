package com.devhub.module.ai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("ai_app")
public class AiApp {
    @TableId(type = IdType.AUTO)
    Long id;
    String appKey;
    String name;
    String description;
    String systemPrompt;
    String openingGreeting;
    Long deptId;
    Integer rateLimitPerMin;
    String allowedOrigins;
    Integer enabled;
    String createdBy;
    LocalDateTime createTime;
    LocalDateTime updateTime;
}
