package com.devhub.module.ai.infrastructure.openapi;

import com.devhub.module.ai.infrastructure.cache.RedisCacheService;
import com.devhub.module.ai.mapper.AiAppMapper;
import com.devhub.module.ai.mapper.ApiKeyMapper;
import com.devhub.module.ai.model.entity.AiApp;
import com.devhub.module.ai.model.entity.ApiKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenApiAuthServiceTest {

    @Mock AiAppMapper appMapper;
    @Mock ApiKeyMapper keyMapper;
    @Mock RedisCacheService cacheService;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    private OpenApiAuthService service;

    @BeforeEach
    void setUp() { service = new OpenApiAuthService(appMapper, keyMapper, cacheService, redis); }

    private AiApp app() {
        AiApp a = new AiApp();
        a.setId(1L); a.setAppKey("app-abc123"); a.setEnabled(1);
        a.setRateLimitPerMin(2); a.setAllowedOrigins(null);
        return a;
    }

    @Test
    @DisplayName("apiKey 校验：命中缓存直接返回应用，零 DB")
    void authenticateKey_cacheHit() {
        when(cacheService.get(startsWith("open:key:"), eq(AiApp.class))).thenReturn(Optional.of(app()));
        Optional<AiApp> r = service.authenticateKey("dh-raw");
        assertTrue(r.isPresent());
        verifyNoInteractions(keyMapper, appMapper);
    }

    @Test
    @DisplayName("apiKey 校验：无缓存查库，key禁用返回空且不缓存应用")
    void authenticateKey_disabledKey() {
        when(cacheService.get(anyString(), eq(AiApp.class))).thenReturn(Optional.empty());
        ApiKey k = new ApiKey();
        k.setAppId(1L); k.setEnabled(0);
        when(keyMapper.selectOne(any())).thenReturn(k);
        assertTrue(service.authenticateKey("dh-raw").isEmpty());
    }

    @Test
    @DisplayName("apiKey 校验：过期返回空")
    void authenticateKey_expired() {
        when(cacheService.get(anyString(), eq(AiApp.class))).thenReturn(Optional.empty());
        ApiKey k = new ApiKey();
        k.setAppId(1L); k.setEnabled(1); k.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(keyMapper.selectOne(any())).thenReturn(k);
        assertTrue(service.authenticateKey("dh-raw").isEmpty());
    }

    @Test
    @DisplayName("apiKey 校验：合法且应用启用 → 返回应用并回填缓存")
    void authenticateKey_ok_backfillsCache() {
        when(cacheService.get(anyString(), eq(AiApp.class))).thenReturn(Optional.empty());
        ApiKey k = new ApiKey();
        k.setId(9L); k.setAppId(1L); k.setEnabled(1); k.setExpiresAt(LocalDateTime.now().plusDays(30));
        when(keyMapper.selectOne(any())).thenReturn(k);
        when(appMapper.selectById(1L)).thenReturn(app());
        Optional<AiApp> r = service.authenticateKey("dh-raw");
        assertTrue(r.isPresent());
        verify(cacheService).set(startsWith("open:key:"), eq(app()), any(Duration.class));
        verify(keyMapper).updateById(argThat((ApiKey x) -> x.getLastUsedAt() != null));
    }

    @Test
    @DisplayName("appKey 校验：应用禁用返回空")
    void authenticateApp_disabled() {
        when(cacheService.get(eq("open:app:app-abc123"), eq(AiApp.class))).thenReturn(Optional.empty());
        AiApp a = app(); a.setEnabled(0);
        when(appMapper.selectOne(any())).thenReturn(a);
        assertTrue(service.authenticateApp("app-abc123").isEmpty());
    }

    @Test
    @DisplayName("限流：分钟窗口内超过 app 上限返回 false")
    void allowRate_overLimit() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(startsWith("open:rl:"))).thenReturn(1L, 2L, 3L);
        AiApp a = app(); // limit 2
        assertTrue(service.allowRate(a));
        assertTrue(service.allowRate(a));
        assertFalse(service.allowRate(a));
    }

    @Test
    @DisplayName("Referer 白名单：配置为空放行；配置非空且不匹配则拒绝")
    void originAllowed() {
        AiApp a = app();
        assertTrue(service.originAllowed(a, "https://evil.com")); // null 白名单=不限
        a.setAllowedOrigins("https://oa.corp.com,https://portal.corp.com");
        assertTrue(service.originAllowed(a, "https://oa.corp.com/faq"));
        assertFalse(service.originAllowed(a, "https://evil.com"));
        assertFalse(service.originAllowed(a, null));
    }
}
