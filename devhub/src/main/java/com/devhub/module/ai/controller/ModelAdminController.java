package com.devhub.module.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.config.LlmProperties;
import com.devhub.module.ai.infrastructure.llm.ChatModelRegistry;
import com.devhub.module.ai.mapper.AiModelConfigMapper;
import com.devhub.module.ai.model.entity.AiModelConfig;
import com.devhub.module.ai.service.ModelConfigService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 模型配置管理（仅管理员）
 * <p>密钥不回传前端（只回传 keySource：env=环境变量 / db=库内配置 / none=未配置）；
 * 每次变更后热重建注册中心，问答立即按新路由生效。
 */
@RestController
@RequestMapping("/api/v1/admin/models")
@RequiredArgsConstructor
public class ModelAdminController {

    private final AiModelConfigMapper mapper;
    private final ModelConfigService configService;
    private final ChatModelRegistry registry;
    private final LlmProperties properties;

    @GetMapping
    public Result<Map<String, Object>> list() {
        if (!isAdmin()) return Result.fail(403, "仅管理员可访问");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (AiModelConfig cfg : configService.listAll()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", cfg.getId());
            item.put("name", cfg.getName());
            item.put("provider", cfg.getProvider());
            item.put("model", cfg.getModel());
            item.put("baseUrl", cfg.getBaseUrl());
            item.put("temperature", cfg.getTemperature());
            item.put("maxTokens", cfg.getMaxTokens());
            item.put("enabled", cfg.getEnabled());
            item.put("isPrimary", cfg.getIsPrimary());
            item.put("remark", cfg.getRemark());
            item.put("updateTime", cfg.getUpdateTime());
            item.put("keySource", keySource(cfg));
            item.put("active", registry.has(cfg.getName()));
            rows.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", rows);
        data.put("primary", registry.primaryName());
        data.put("fallbackChain", registry.fallbackNames());
        return Result.success(data);
    }

    @PostMapping
    public Result<String> create(@RequestBody ModelConfigBody body) {
        if (!isAdmin()) return Result.fail(403, "仅管理员可访问");
        if (isBlank(body.getName()) || isBlank(body.getModel()) || isBlank(body.getBaseUrl())) {
            return Result.fail(400, "别名、模型ID、Base URL 不能为空");
        }
        if (mapper.selectCount(new LambdaQueryWrapper<AiModelConfig>().eq(AiModelConfig::getName, body.getName())) > 0) {
            return Result.fail(409, "模型别名已存在: " + body.getName());
        }
        AiModelConfig cfg = AiModelConfig.builder()
                .name(body.getName().trim())
                .provider(nvl(body.getProvider(), "other"))
                .model(body.getModel().trim())
                .baseUrl(body.getBaseUrl().trim())
                .apiKey(trimToNull(body.getApiKey()))
                .temperature(body.getTemperature() != null ? body.getTemperature() : 0.7)
                .maxTokens(body.getMaxTokens() != null ? body.getMaxTokens() : 4096)
                .enabled(1)
                .isPrimary(0)
                .remark(body.getRemark())
                .build();
        mapper.insert(cfg);
        return apply("新增成功");
    }

    @PutMapping("/{id}")
    public Result<String> update(@PathVariable Long id, @RequestBody ModelConfigBody body) {
        if (!isAdmin()) return Result.fail(403, "仅管理员可访问");
        AiModelConfig cfg = mapper.selectById(id);
        if (cfg == null) return Result.fail(404, "配置不存在");

        AiModelConfig patch = new AiModelConfig();
        patch.setId(id);
        if (isBlank(body.getModel()) || isBlank(body.getBaseUrl())) {
            return Result.fail(400, "模型ID、Base URL 不能为空");
        }
        patch.setModel(body.getModel().trim());
        patch.setBaseUrl(body.getBaseUrl().trim());
        patch.setProvider(nvl(body.getProvider(), cfg.getProvider()));
        patch.setTemperature(body.getTemperature() != null ? body.getTemperature() : cfg.getTemperature());
        patch.setMaxTokens(body.getMaxTokens() != null ? body.getMaxTokens() : cfg.getMaxTokens());
        patch.setRemark(body.getRemark());
        // apiKey 字段缺省(null)=保持不变；空字符串=清空（回到继承环境变量）
        if (body.getApiKey() != null) patch.setApiKey(trimToNull(body.getApiKey()));
        mapper.updateById(patch);

        return apply("保存成功");
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        if (!isAdmin()) return Result.fail(403, "仅管理员可访问");
        AiModelConfig cfg = mapper.selectById(id);
        if (cfg == null) return Result.fail(404, "配置不存在");
        if (cfg.getIsPrimary() == 1) return Result.fail(400, "不能删除主模型，请先把其他模型设为主模型");
        mapper.deleteById(id);
        String err = configService.applyOverlay();
        return err == null ? Result.success("已删除") : Result.success("已删除（" + err + "）");
    }

    @PostMapping("/{id}/primary")
    public Result<String> setPrimary(@PathVariable Long id) {
        if (!isAdmin()) return Result.fail(403, "仅管理员可访问");
        AiModelConfig cfg = mapper.selectById(id);
        if (cfg == null) return Result.fail(404, "配置不存在");
        if (cfg.getEnabled() == 0) return Result.fail(400, "已停用的模型不能设为主模型");

        AiModelConfig clear = new AiModelConfig();
        for (AiModelConfig row : configService.listAll()) {
            if (row.getIsPrimary() == 1 && !row.getId().equals(id)) {
                clear.setId(row.getId());
                clear.setIsPrimary(0);
                mapper.updateById(clear);
            }
        }
        AiModelConfig patch = new AiModelConfig();
        patch.setId(id);
        patch.setIsPrimary(1);
        mapper.updateById(patch);
        return apply("已设为主模型");
    }

    @PostMapping("/{id}/toggle")
    public Result<String> toggle(@PathVariable Long id) {
        if (!isAdmin()) return Result.fail(403, "仅管理员可访问");
        AiModelConfig cfg = mapper.selectById(id);
        if (cfg == null) return Result.fail(404, "配置不存在");
        if (cfg.getEnabled() == 1 && cfg.getIsPrimary() == 1) {
            return Result.fail(400, "主模型不能停用，请先切换主模型");
        }
        AiModelConfig patch = new AiModelConfig();
        patch.setId(id);
        patch.setEnabled(cfg.getEnabled() == 1 ? 0 : 1);
        mapper.updateById(patch);
        return apply(cfg.getEnabled() == 1 ? "已停用" : "已启用");
    }

    /** 应用叠加；失败直接把原因返回给前端（注册中心保持原状，DB 行保留待修正） */
    private Result<String> apply(String okMsg) {
        String err = configService.applyOverlay();
        if (err == null) return Result.success(okMsg);
        return Result.fail(400, err);
    }

    private String keySource(AiModelConfig cfg) {
        if (cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) return "db";
        LlmProperties.ModelSpec env = properties.getModels().get(cfg.getName());
        return env != null && env.getApiKey() != null && !env.getApiKey().isBlank() ? "env" : "none";
    }

    private static boolean isAdmin() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().contains("ADMIN"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static String nvl(String s, String def) {
        return isBlank(s) ? def : s.trim();
    }

    @Data
    public static class ModelConfigBody {
        private String name;
        private String provider;
        private String model;
        private String baseUrl;
        private String apiKey;
        private Double temperature;
        private Integer maxTokens;
        private String remark;
    }
}
