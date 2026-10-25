package com.devhub.module.ai.service;

import com.devhub.module.ai.config.LlmProperties;
import com.devhub.module.ai.infrastructure.llm.ChatModelRegistry;
import com.devhub.module.ai.mapper.AiModelConfigMapper;
import com.devhub.module.ai.model.entity.AiModelConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型配置服务：DB 配置叠加在环境变量配置之上，变更后热重建注册中心（无需重启）。
 * <p>叠加规则：
 * <ul>
 *   <li>以环境变量 agent.llm.models 为基线，DB 同名行覆盖 参数/地址/模型ID/密钥/启停/主模型；</li>
 *   <li>DB 新增别名且填了库内密钥、或环境变量已有其密钥 → 可用；</li>
 *   <li>DB enabled=0 → 从注册表摘除；DB is_primary=1 → 作为主模型；</li>
 *   <li>主模型不可用时回退环境变量主模型，仍不可用则放弃本次重建（保持现状）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConfigService {

    private final AiModelConfigMapper mapper;
    private final ChatModelRegistry registry;
    private final LlmProperties properties;

    /** 启动即应用一次 DB 叠加（Flyway 已先于本事件完成建表/种子） */
    @EventListener(ApplicationReadyEvent.class)
    public void applyOnStartup() {
        try {
            String err = applyOverlay();
            if (err != null) log.warn("启动叠加模型配置失败: {}", err);
        } catch (Exception e) {
            log.warn("启动叠加模型配置异常，沿用环境变量配置: {}", e.getMessage());
        }
    }

    public List<AiModelConfig> listAll() {
        return mapper.selectList(null);
    }

    /**
     * 计算环境变量 + DB 的最终生效配置（不触发重建）
     */
    public Effective computeEffective() {
        Map<String, LlmProperties.ModelSpec> specs = new LinkedHashMap<>();
        properties.getModels().forEach((name, spec) -> specs.put(name, copy(spec)));

        String primary = properties.getPrimary();
        List<AiModelConfig> rows = listAll();
        for (AiModelConfig row : rows) {
            if (row.getEnabled() == 0) {
                specs.remove(row.getName());
                continue;
            }
            LlmProperties.ModelSpec spec = specs.get(row.getName());
            if (spec == null) {
                spec = new LlmProperties.ModelSpec();
                specs.put(row.getName(), spec);
            }
            if (notBlank(row.getBaseUrl())) spec.setBaseUrl(row.getBaseUrl());
            if (notBlank(row.getModel())) spec.setModel(row.getModel());
            if (row.getTemperature() != null) spec.setTemperature(row.getTemperature());
            if (row.getMaxTokens() != null) spec.setMaxTokens(row.getMaxTokens());
            if (notBlank(row.getRemark())) spec.setDescription(row.getRemark());
            // 库内显式密钥覆盖环境变量；留空则继承
            if (notBlank(row.getApiKey())) spec.setApiKey(row.getApiKey());
            if (row.getIsPrimary() == 1) primary = row.getName();
        }

        // 降级链：环境变量顺序优先，其余可用模型补位（排除主模型）
        List<String> fallback = new ArrayList<>();
        for (String name : properties.getFallback()) {
            if (specs.containsKey(name)) fallback.add(name);
        }
        for (AiModelConfig row : rows) {
            String name = row.getName();
            if (row.getEnabled() == 1 && specs.containsKey(name)
                    && !fallback.contains(name) && !name.equals(primary)) {
                fallback.add(name);
            }
        }
        return new Effective(specs, primary, fallback);
    }

    /**
     * 应用叠加并热重建注册中心；返回 null 表示成功，否则为失败原因（保持原注册表不变）
     */
    public synchronized String applyOverlay() {
        Effective eff = computeEffective();
        String primary = eff.primary();
        LlmProperties.ModelSpec primarySpec = eff.specs().get(primary);
        if (primarySpec == null || !notBlank(primarySpec.getApiKey())) {
            primary = properties.getPrimary();
            primarySpec = eff.specs().get(primary);
        }
        if (primarySpec == null || !notBlank(primarySpec.getApiKey())) {
            return "无可用主模型（未配置密钥），已保持现有配置";
        }
        registry.rebuild(eff.specs(), primary, eff.fallback());
        return null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static LlmProperties.ModelSpec copy(LlmProperties.ModelSpec src) {
        LlmProperties.ModelSpec out = new LlmProperties.ModelSpec();
        out.setBaseUrl(src.getBaseUrl());
        out.setApiKey(src.getApiKey());
        out.setModel(src.getModel());
        out.setTemperature(src.getTemperature());
        out.setMaxTokens(src.getMaxTokens());
        out.setDescription(src.getDescription());
        return out;
    }

    /** 叠加计算结果：specs 已含最终参数与密钥 */
    public record Effective(Map<String, LlmProperties.ModelSpec> specs, String primary, List<String> fallback) {
    }
}
