package com.devhub.module.ai.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 缓存三防单元测试：穿透（空值占位）、击穿（互斥重建）、雪崩（TTL 抖动）
 */
@ExtendWith(MockitoExtension.class)
class RedisCacheServiceTest {

    private static final String KEY = "k";
    private static final String FULL_KEY = "agent:k";
    private static final String LOCK_KEY = "agent:k:lock";
    private static final String TOMBSTONE = "__DEVHUB_NULL__";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private RedisCacheService service;

    @BeforeEach
    void setUp() {
        service = new RedisCacheService(redisTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("命中缓存：直接返回，不触发回源")
    void getOrLoad_cacheHit_skipsLoader() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(FULL_KEY)).thenReturn("{\"v\":1}");

        AtomicInteger loads = new AtomicInteger();
        Optional<Map> result = service.getOrLoad(KEY, Map.class,
                () -> { loads.incrementAndGet(); return Map.of("v", 2); }, Duration.ofMinutes(10));

        assertTrue(result.isPresent());
        assertEquals(1, ((Number) result.get().get("v")).intValue());
        assertEquals(0, loads.get());
    }

    @Test
    @DisplayName("防穿透：回源为 null 时写短 TTL 空值占位")
    void getOrLoad_nullLoader_writesTombstone() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(FULL_KEY)).thenReturn(null);
        when(valueOps.setIfAbsent(eq(LOCK_KEY), eq("1"), any(Duration.class))).thenReturn(true);

        Optional<Map> result = service.getOrLoad(KEY, Map.class, () -> null, Duration.ofMinutes(10));

        assertFalse(result.isPresent());
        verify(valueOps).set(eq(FULL_KEY), eq(TOMBSTONE), eq(Duration.ofSeconds(60)));
        verify(redisTemplate).delete(LOCK_KEY);
    }

    @Test
    @DisplayName("防穿透：get 遇到空值占位返回 empty（调用方无需回源）")
    void get_tombstone_returnsEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(FULL_KEY)).thenReturn(TOMBSTONE);

        assertTrue(service.get(KEY, Map.class).isEmpty());
    }

    @Test
    @DisplayName("防击穿：未抢到锁时自旋等缓存，绝不重复回源")
    void getOrLoad_lockNotAcquired_waitsForWinner() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(FULL_KEY)).thenReturn(null, "{\"v\":1}");
        when(valueOps.setIfAbsent(eq(LOCK_KEY), eq("1"), any(Duration.class))).thenReturn(false);
        when(redisTemplate.hasKey(FULL_KEY)).thenReturn(false, false, true);

        AtomicInteger loads = new AtomicInteger();
        Optional<Map> result = service.getOrLoad(KEY, Map.class,
                () -> { loads.incrementAndGet(); return Map.of("v", 2); }, Duration.ofMinutes(10));

        assertTrue(result.isPresent());
        assertEquals(0, loads.get());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("防击穿兜底：自旋超时降级直接回源（可用性优先）")
    void getOrLoad_spinTimeout_degradesToLoader() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(FULL_KEY)).thenReturn(null);
        when(valueOps.setIfAbsent(eq(LOCK_KEY), eq("1"), any(Duration.class))).thenReturn(false);
        when(redisTemplate.hasKey(FULL_KEY)).thenReturn(false);

        Optional<Map> result = service.getOrLoad(KEY, Map.class,
                () -> Map.of("v", 7), Duration.ofMinutes(10));

        assertTrue(result.isPresent());
        assertEquals(7, ((Number) result.get().get("v")).intValue());
    }

    @Test
    @DisplayName("正常回源：写入经抖动的 TTL，并释放重建锁")
    void getOrLoad_rebuild_jitteredTtlAndUnlock() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(FULL_KEY)).thenReturn(null);
        when(valueOps.setIfAbsent(eq(LOCK_KEY), eq("1"), any(Duration.class))).thenReturn(true);

        Duration base = Duration.ofMinutes(10);
        Optional<Map> result = service.getOrLoad(KEY, Map.class, () -> Map.of("v", 3), base);

        assertTrue(result.isPresent());
        verify(valueOps).set(eq(FULL_KEY), eq("{\"v\":3}"), argThat(ttl ->
                ttl.toMillis() >= base.toMillis() * 0.8 && ttl.toMillis() <= base.toMillis() * 1.2));
        verify(redisTemplate).delete(LOCK_KEY);
    }

    @Test
    @DisplayName("防雪崩：jitter 保持在基准 ±20% 区间内")
    void jitter_staysWithinBounds() {
        Duration base = Duration.ofHours(1);
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (int i = 0; i < 500; i++) {
            long ms = service.jitter(base).toMillis();
            min = Math.min(min, ms);
            max = Math.max(max, ms);
        }
        assertTrue(min >= base.toMillis() * 0.8, "下界越界: " + min);
        assertTrue(max <= base.toMillis() * 1.2, "上界越界: " + max);
        assertTrue(min < max, "抖动未生效（全为同值）");
    }
}
