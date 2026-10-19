package com.devhub.module.ai.infrastructure.llm;

import com.devhub.module.ai.config.LlmProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多模型注册中心
 * <p>
 * 启动时根据 agent.llm.models 配置构建同步/流式两套模型实例，
 * api-key 为空的模型自动跳过。所有模型统一走 OpenAI 兼容协议，
 * 通过不同 base-url 覆盖智谱、DeepSeek、Qwen 等国内外 provider。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class ChatModelRegistry {

    /** Embedding 服务单次请求的输入条数上限（智谱 embedding-3 限制 64 条/次） */
    private static final int EMBED_BATCH_SIZE = 64;

    private final LlmProperties properties;
    private final Map<String, ChatModel> chatModels = new LinkedHashMap<>();
    private final Map<String, StreamingChatModel> streamingModels = new LinkedHashMap<>();
    private EmbeddingModel embeddingModel;

    public ChatModelRegistry(LlmProperties properties) {
        this.properties = properties;
        properties.getModels().forEach((name, spec) -> {
            if (spec.getApiKey() == null || spec.getApiKey().isBlank()) {
                log.info("跳过未配置 api-key 的模型: {} ({})", name, spec.getDescription());
                return;
            }
            chatModels.put(name, buildChatModel(spec));
            streamingModels.put(name, buildStreamingModel(spec));
            log.info("注册模型: {} -> {} @ {}", name, spec.getModel(), spec.getBaseUrl());
        });

        if (!chatModels.containsKey(properties.getPrimary())) {
            throw new IllegalStateException(
                    "主模型 [" + properties.getPrimary() + "] 未注册，请检查 agent.llm 配置（可用: " + chatModels.keySet() + "）");
        }
        log.info("多模型注册完成: primary={}, fallback={}, 可用={}",
                properties.getPrimary(), properties.getFallback(), chatModels.keySet());

        LlmProperties.EmbeddingSpec emb = properties.getEmbedding();
        if (emb != null && emb.getApiKey() != null && !emb.getApiKey().isBlank()) {
            OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder = OpenAiEmbeddingModel.builder()
                    .baseUrl(emb.getBaseUrl())
                    .apiKey(emb.getApiKey())
                    .modelName(emb.getModel());
            if (emb.getDimensions() != null) {
                builder.dimensions(emb.getDimensions());
            }
            this.embeddingModel = builder.build();
            log.info("注册向量模型: {} (dim={})", emb.getModel(), emb.getDimensions());
        } else {
            log.warn("未配置向量模型 api-key，文档向量化与语义检索不可用");
        }
    }

    /**
     * 向量模型（RAG 检索用）
     */
    public EmbeddingModel embedding() {
        if (embeddingModel == null) {
            throw new IllegalStateException("向量模型未注册，请配置 agent.llm.embedding.api-key");
        }
        return embeddingModel;
    }

    /**
     * 单文本向量化（转换为 Milvus 需要的 List<Float>）
     */
    public List<Float> embedToVector(String text) {
        float[] vector = embedding().embed(text).content().vector();
        List<Float> result = new ArrayList<>(vector.length);
        for (float f : vector) {
            result.add(f);
        }
        return result;
    }

    /**
     * 批量文本向量化（按 EMBED_BATCH_SIZE 分批，规避 Embedding 服务单次条数上限）
     */
    public List<List<Float>> embedAllToVectors(List<String> texts) {
        List<List<Float>> result = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += EMBED_BATCH_SIZE) {
            List<TextSegment> batch = texts.subList(from, Math.min(from + EMBED_BATCH_SIZE, texts.size()))
                    .stream().map(TextSegment::from).toList();
            embedding().embedAll(batch).content().forEach(embedding -> {
                float[] vector = embedding.vector();
                List<Float> list = new ArrayList<>(vector.length);
                for (float f : vector) {
                    list.add(f);
                }
                result.add(list);
            });
        }
        return result;
    }

    public boolean embeddingEnabled() {
        return embeddingModel != null;
    }

    public ChatModel get(String name) {
        ChatModel model = chatModels.get(name);
        if (model == null) {
            throw new IllegalArgumentException("模型未注册: " + name + "，可用: " + chatModels.keySet());
        }
        return model;
    }

    public StreamingChatModel streaming(String name) {
        StreamingChatModel model = streamingModels.get(name);
        if (model == null) {
            throw new IllegalArgumentException("流式模型未注册: " + name + "，可用: " + streamingModels.keySet());
        }
        return model;
    }

    public boolean has(String name) {
        return chatModels.containsKey(name);
    }

    public Set<String> names() {
        return chatModels.keySet();
    }

    public String primaryName() {
        return properties.getPrimary();
    }

    /** 轻量模型：降级链第一个可用模型，用于意图识别、摘要等低价值调用；无降级则用主模型 */
    public String lightModelName() {
        for (String name : properties.getFallback()) {
            if (chatModels.containsKey(name)) {
                return name;
            }
        }
        return properties.getPrimary();
    }

    /** 有效的降级链（仅保留已注册模型，主模型本身不在其中） */
    public List<String> fallbackNames() {
        List<String> chain = new ArrayList<>();
        for (String name : properties.getFallback()) {
            if (chatModels.containsKey(name) && !chain.contains(name)) {
                chain.add(name);
            }
        }
        return chain;
    }

    public LlmProperties.ModelSpec spec(String name) {
        return properties.getModels().get(name);
    }

    private ChatModel buildChatModel(LlmProperties.ModelSpec spec) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(spec.getBaseUrl())
                .apiKey(spec.getApiKey())
                .modelName(spec.getModel())
                .temperature(spec.getTemperature());
        if (spec.getMaxTokens() != null) {
            builder.maxTokens(spec.getMaxTokens());
        }
        return builder.build();
    }

    private StreamingChatModel buildStreamingModel(LlmProperties.ModelSpec spec) {
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .baseUrl(spec.getBaseUrl())
                .apiKey(spec.getApiKey())
                .modelName(spec.getModel())
                .temperature(spec.getTemperature());
        if (spec.getMaxTokens() != null) {
            builder.maxTokens(spec.getMaxTokens());
        }
        return builder.build();
    }
}
