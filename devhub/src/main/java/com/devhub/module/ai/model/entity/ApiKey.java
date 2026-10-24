package com.devhub.module.ai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("api_key")
public class ApiKey {
    @TableId(type = IdType.AUTO)
    Long id;
    Long appId;
    String keyHash;
    String keyPrefix;
    String name;
    Integer enabled;
    LocalDateTime expiresAt;
    LocalDateTime lastUsedAt;
    LocalDateTime createTime;
    LocalDateTime updateTime;
}
