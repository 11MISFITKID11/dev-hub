package com.devhub.module.ai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 模型配置（管理端可维护，运行时叠加在环境变量配置之上）
 * <p>api_key 留空 = 继承环境变量密钥（推荐，密钥不落库）；显式填写则覆盖环境变量。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ai_model_config")
public class AiModelConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 模型别名（路由键，唯一） */
    private String name;
    /** zhipu / deepseek / qwen / other */
    private String provider;
    /** 供应商侧模型 ID */
    private String model;
    private String baseUrl;
    /** 留空 = 继承环境变量 */
    private String apiKey;
    private Double temperature;
    private Integer maxTokens;
    private Integer enabled;
    private Integer isPrimary;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
