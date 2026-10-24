package com.devhub.module.ai.infrastructure.openapi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devhub.module.ai.infrastructure.cache.RedisCacheService;
import com.devhub.module.ai.mapper.AiAppMapper;
import com.devhub.module.ai.mapper.ApiKeyMapper;
import com.devhub.module.ai.model.entity.AiApp;
import com.devhub.module.ai.model.entity.ApiKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 开放 API 凭据校验 + 应用级限流 + 挂件 Referer 白名单。
 * 校验结果 Redis 缓存 5 分钟；应用/key 禁用时由管理接口调用 invalidate 主动失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiAuthService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final AiAppMapper appMapper;
    private final ApiKeyMapper keyMapper;
    private final RedisCacheService cacheService;
    private final StringRedisTemplate redis;

    /** apiKey（保密凭据）→ 所属应用；无效/禁用/过期/应用下线返回空 */
    public Optional<AiApp> authenticateKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return Optional.empty();
        String hash = sha256(rawKey);
        String key = "open:key:" + hash;
        Optional<AiApp> cached = cacheService.get(key, AiApp.class);
        if (cached.isPresent()) return cached;
        try {
            ApiKey k = keyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                    .eq(ApiKey::getKeyHash, hash).last("LIMIT 1"));
            if (k == null || k.getEnabled() != 1) return Optional.empty();
            if (k.getExpiresAt() != null && k.getExpiresAt().isBefore(LocalDateTime.now())) return Optional.empty();
            AiApp app = appMapper.selectById(k.getAppId());
            if (app == null || app.getEnabled() != 1) return Optional.empty();
            cacheService.set(key, app, CACHE_TTL);
            try {
                ApiKey touch = new ApiKey();
                touch.setId(k.getId());
                touch.setLastUsedAt(LocalDateTime.now());
                keyMapper.updateById(touch);
            } catch (Exception ignored) { }
            return Optional.of(app);
        } catch (Exception e) {
            log.warn("apiKey 校验失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** appKey（公开凭据，挂件用）→ 应用 */
    public Optional<AiApp> authenticateApp(String appKey) {
        if (appKey == null || appKey.isBlank()) return Optional.empty();
        String key = "open:app:" + appKey;
        Optional<AiApp> cached = cacheService.get(key, AiApp.class);
        if (cached.isPresent()) return cached;
        try {
            AiApp app = appMapper.selectOne(new LambdaQueryWrapper<AiApp>()
                    .eq(AiApp::getAppKey, appKey).last("LIMIT 1"));
            if (app == null || app.getEnabled() != 1) return Optional.empty();
            cacheService.set(key, app, CACHE_TTL);
            return Optional.of(app);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 应用禁用/编辑后失效缓存（管理接口调用） */
    public void invalidateApp(String appKey) {
        cacheService.delete("open:app:" + appKey);
    }

    /** 应用级限流：每分钟固定窗口，Redis 故障时放行（限流不阻断服务） */
    public boolean allowRate(AiApp app) {
        int limit = app.getRateLimitPerMin() == null ? 20 : app.getRateLimitPerMin();
        try {
            String window = String.valueOf(System.currentTimeMillis() / 60_000);
            String key = "open:rl:" + app.getAppKey() + ":" + window;
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) redis.expire(key, Duration.ofSeconds(70));
            return count == null || count <= limit;
        } catch (Exception e) {
            return true;
        }
    }

    /** Referer 是否在应用白名单内；白名单为空 = 不限来源 */
    public boolean originAllowed(AiApp app, String referer) {
        String allow = app.getAllowedOrigins();
        if (allow == null || allow.isBlank()) return true;
        if (referer == null || referer.isBlank()) return false;
        for (String o : allow.split(",")) {
            String prefix = o.trim();
            if (!prefix.isEmpty() && referer.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * 设置检索域覆盖并执行动作，finally 清理（开放链路 Controller 唯一入口，便于测试）。
     * 检索发生在 chat/chatStream 调用期间（同步），Flux 异步消费不触碰检索域
     */
    public <T> T setScopeThen(String scope, Supplier<T> action) {
        com.devhub.module.ai.service.rag.MultiRetriever.setScopeOverride(scope);
        try {
            return action.get();
        } finally {
            com.devhub.module.ai.service.rag.MultiRetriever.clearScopeOverride();
        }
    }

    public String sha256(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
