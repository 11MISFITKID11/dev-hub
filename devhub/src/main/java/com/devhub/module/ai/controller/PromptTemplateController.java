package com.devhub.module.ai.controller;

import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.mapper.PromptTemplateMapper;
import com.devhub.module.ai.model.entity.PromptTemplate;
import com.devhub.module.ai.service.PromptTemplateService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 模板管理（仅管理员）：按场景查看默认/覆盖模板，编辑即热生效，可一键恢复默认。
 */
@RestController
@RequestMapping("/api/v1/admin/prompts")
@RequiredArgsConstructor
public class PromptTemplateController {

    private final PromptTemplateService service;
    private final PromptTemplateMapper mapper;

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        if (!isAdmin()) return Result.fail(403, "仅管理员可访问");
        List<Map<String, Object>> rows = new ArrayList<>();
        service.scenes().forEach((scene, def) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scene", scene);
            m.put("name", def.name());
            m.put("vars", def.vars());
            m.put("default", def.template());
            m.put("overridden", service.overridden(scene));
            PromptTemplate row = mapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                    .LambdaQueryWrapper<PromptTemplate>().eq(PromptTemplate::getScene, scene));
            if (row != null) {
                m.put("content", row.getContent());
                m.put("updateBy", row.getUpdateBy());
                m.put("updateTime", row.getUpdateTime());
            }
            rows.add(m);
        });
        return Result.success(rows);
    }

    /** 保存覆盖（content 为空 = 恢复默认） */
    @PutMapping("/{scene}")
    public Result<String> save(@PathVariable String scene, @RequestBody PromptBody body) {
        if (!isAdmin()) return Result.fail(403, "仅管理员可访问");
        String me = SecurityContextHolder.getContext().getAuthentication().getName();
        service.saveOverride(scene, body.getContent(), me);
        return Result.success(body.getContent() == null || body.getContent().isBlank()
                ? "已恢复默认模板" : "已保存并热生效");
    }

    private static boolean isAdmin() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().contains("ADMIN"));
    }

    @Data
    public static class PromptBody {
        private String content;
    }
}
