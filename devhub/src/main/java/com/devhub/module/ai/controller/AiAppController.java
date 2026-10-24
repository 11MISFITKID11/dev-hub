package com.devhub.module.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.devhub.common.OperLog;
import com.devhub.module.ai.common.BusinessException;
import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.infrastructure.openapi.OpenApiAuthService;
import com.devhub.module.ai.mapper.AiAppMapper;
import com.devhub.module.ai.mapper.ApiKeyMapper;
import com.devhub.module.ai.model.entity.AiApp;
import com.devhub.module.ai.model.entity.ApiKey;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 应用与 API Key 管理（JWT 鉴权；编辑/禁用限创建人或 ADMIN）。
 * apiKey 明文 dh-{32hex} 仅创建响应返回一次；禁用应用/key 即失效凭据缓存
 */
@RestController
@RequestMapping("/api/apps")
@RequiredArgsConstructor
public class AiAppController {

    private final AiAppMapper appMapper;
    private final ApiKeyMapper keyMapper;
    private final OpenApiAuthService auth;
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    @GetMapping
    public Result<Page<AiApp>> list(@RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "10") long size,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(defaultValue = "true") boolean activeOnly) {
        LambdaQueryWrapper<AiApp> q = new LambdaQueryWrapper<AiApp>()
                .like(keyword != null && !keyword.isBlank(), AiApp::getName, keyword)
                .eq(activeOnly, AiApp::getEnabled, 1)
                .orderByDesc(AiApp::getUpdateTime);
        return Result.success(appMapper.selectPage(new Page<>(page, size), q));
    }

    @OperLog(module = "AI应用", action = "创建应用")
    @PostMapping
    public Result<AiApp> create(@RequestBody AiApp app) {
        if (app.getName() == null || app.getName().isBlank()) {
            throw new BusinessException("应用名称不能为空");
        }
        String me = currentUsername();
        app.setId(null);
        app.setAppKey("app-" + hex(12));
        app.setEnabled(app.getEnabled() == null ? 1 : app.getEnabled());
        app.setCreatedBy(me);
        if (app.getDeptId() == null) {
            try {
                app.setDeptId(jdbc.queryForObject(
                        "SELECT dept_id FROM sys_user WHERE username = ? AND deleted = 0",
                        Long.class, me));
            } catch (Exception ignored) { }
        }
        appMapper.insert(app);
        return Result.success(app);
    }

    @OperLog(module = "AI应用", action = "更新应用")
    @PutMapping("/{id}")
    public Result<String> update(@PathVariable Long id, @RequestBody AiApp patch) {
        AiApp app = requireOwned(id);
        patch.setId(id);
        patch.setAppKey(null);          // 凭据不可改
        patch.setCreatedBy(null);
        appMapper.updateById(patch);
        auth.invalidateApp(app.getAppKey());
        return Result.success("ok");
    }

    @OperLog(module = "AI应用", action = "下线应用")
    @DeleteMapping("/{id}")
    public Result<String> disable(@PathVariable Long id) {
        AiApp app = requireOwned(id);
        AiApp patch = new AiApp();
        patch.setId(id);
        patch.setEnabled(0);
        appMapper.updateById(patch);
        auth.invalidateApp(app.getAppKey());
        return Result.success("ok");
    }

    @GetMapping("/{id}/keys")
    public Result<List<ApiKey>> keys(@PathVariable Long id) {
        requireOwned(id);
        return Result.success(keyMapper.selectList(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getAppId, id).orderByDesc(ApiKey::getId)));
    }

    /** 创建 key：明文仅本次响应返回（后台只存 SHA-256） */
    @OperLog(module = "AI应用", action = "创建API密钥")
    @PostMapping("/{id}/keys")
    public Result<Map<String, Object>> createKey(@PathVariable Long id,
                                                 @RequestBody(required = false) Map<String, String> body) {
        requireOwned(id);
        String raw = "dh-" + hex(32);
        ApiKey k = new ApiKey();
        k.setAppId(id);
        k.setKeyHash(auth.sha256(raw));
        k.setKeyPrefix(raw.substring(0, 8));
        k.setName(body == null ? "default" : body.getOrDefault("name", "default"));
        k.setEnabled(1);
        keyMapper.insert(k);
        Map<String, Object> out = new HashMap<>();
        out.put("id", k.getId());
        out.put("keyPrefix", k.getKeyPrefix());
        out.put("name", k.getName());
        out.put("secret", raw);   // 唯一一次明文
        return Result.success(out);
    }

    @OperLog(module = "AI应用", action = "切换密钥状态")
    @PutMapping("/{id}/keys/{keyId}")
    public Result<String> toggleKey(@PathVariable Long id, @PathVariable Long keyId,
                                    @RequestParam boolean enabled) {
        requireOwned(id);
        ApiKey patch = new ApiKey();
        patch.setId(keyId);
        patch.setEnabled(enabled ? 1 : 0);
        keyMapper.updateById(patch);
        return Result.success("ok");
    }

    @OperLog(module = "AI应用", action = "删除密钥")
    @DeleteMapping("/{id}/keys/{keyId}")
    public Result<String> deleteKey(@PathVariable Long id, @PathVariable Long keyId) {
        requireOwned(id);
        keyMapper.deleteById(keyId);
        return Result.success("ok");
    }

    private AiApp requireOwned(Long id) {
        AiApp app = appMapper.selectById(id);
        if (app == null) throw new BusinessException(404, "应用不存在");
        String me = currentUsername();
        boolean admin = SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin && !me.equals(app.getCreatedBy())) {
            throw new BusinessException(403, "仅应用创建人或管理员可操作");
        }
        return app;
    }

    private String hex(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append("0123456789abcdef".charAt(random.nextInt(16)));
        return sb.toString();
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
