package com.devhub.module.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多模型配置
 * <p>
 * 在 application.yml 中以 agent.llm 前缀配置任意多个模型，
 * 每个模型独立指定 provider 地址、key、模型名。
 * api-key 为空的模型会被自动跳过（不注册）。
 */
@Data
@ConfigurationProperties(prefix = "agent.llm")
public class LlmProperties {

    /** 默认使用的模型（对应 models 里的 key） */
    private String primary;

    /** 降级链：primary 失败后依次尝试（对应 models 里的 key） */
    private List<String> fallback = new ArrayList<>();

    /** 模型注册表：key 为业务别名，value 为模型规格 */
    private Map<String, ModelSpec> models = new LinkedHashMap<>();

    /** 向量模型（RAG 检索用，OpenAI 兼容协议） */
    private EmbeddingSpec embedding = new EmbeddingSpec();

    @Data
    public static class ModelSpec {
        /** provider 的 OpenAI 兼容地址（如 https://open.bigmodel.cn/api/paas/v4） */
        private String baseUrl;
        /** API Key，为空则跳过该模型 */
        private String apiKey;
        /** 实际请求的模型名（如 glm-4-flash） */
        private String model;
        private double temperature = 0.7;
        private Integer maxTokens;
        private String description = "";
    }

    @Data
    public static class EmbeddingSpec {
        private String baseUrl;
        /** 为空则禁用向量化（RAG 检索将不可用） */
        private String apiKey;
        private String model = "embedding-3";
        /** 输出维度，需与 milvus.dimension 一致 */
        private Integer dimensions = 1024;
    }
}
