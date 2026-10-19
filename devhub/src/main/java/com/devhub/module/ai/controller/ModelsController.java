package com.devhub.module.ai.controller;

import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.infrastructure.llm.ChatModelRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型管理控制器
 * <p>
 * 查询当前注册的多模型信息，前端可据此渲染模型选择器。
 */
@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ModelsController {

    private final ChatModelRegistry registry;

    /**
     * 获取可用模型列表及默认路由信息
     */
    @GetMapping
    public Result<Map<String, Object>> list() {
        List<Map<String, Object>> models = new ArrayList<>();
        registry.names().forEach(name -> {
            var spec = registry.spec(name);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("model", spec.getModel());
            item.put("description", spec.getDescription());
            item.put("primary", name.equals(registry.primaryName()));
            models.add(item);
        });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("models", models);
        data.put("primary", registry.primaryName());
        data.put("fallbackChain", registry.fallbackNames());
        return Result.success(data);
    }
}
