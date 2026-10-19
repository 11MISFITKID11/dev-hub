package com.devhub.module.ai.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Redis 缓存服务
 * <p>
 * 提供类型安全的缓存操作，支持对象序列化/反序列化。
 * getOrLoad 提供带三防（穿透/击穿/雪崩）的 Cache-Aside 读穿。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "agent:";

    /** 空值占位符：非 JSON 结构，不会与任何真实序列化结果混淆 */
    private static final String NULL_TOMBSTONE = "__DEVHUB_NULL__";
    private static final String LOCK_SUFFIX = ":lock";
    /** 空值缓存时长（防穿透）：短 TTL 保证即便数据后来写入也能较快纠正 */
    private static final Duration NULL_TTL = Duration.ofSeconds(60);
    /** 重建互斥锁时长：略高于单次回源预期耗时，超时自动释放防死锁 */
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    /** 未抢到锁时的自旋等待参数 */
    private static final int LOCK_RETRY_TIMES = 3;
    private static final long LOCK_RETRY_INTERVAL_MS = 50;

    /**
     * 写入缓存（带过期时间）
     */
    public <T> void set(String key, T value, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(buildKey(key), json, ttl);
            log.debug("写入缓存: key={}, ttl={}s", key, ttl.getSeconds());
        } catch (JsonProcessingException e) {
            log.error("缓存序列化失败: key={}", key, e);
        }
    }

    /**
     * 读取缓存；空值占位符视为未命中且已被拦截（调用方无需再回源）
     */
    public <T> Optional<T> get(String key, Class<T> clazz) {
        String json = readRaw(key);
        if (json == null) {
            return Optional.empty();
        }
        if (NULL_TOMBSTONE.equals(json)) {
            log.debug("命中空值占位: key={}", key);
            return Optional.empty();
        }

        try {
            T value = objectMapper.readValue(json, clazz);
            log.debug("命中缓存: key={}", key);
            return Optional.of(value);
        } catch (JsonProcessingException e) {
            log.error("缓存反序列化失败: key={}", key, e);
            return Optional.empty();
        }
    }

    /**
     * Cache-Aside 读穿，内建缓存三防：
     * <p>
     * 穿透——回源结果为 null 时写入短 TTL 空值占位，相同 key 在占位存活期内不再打到后端；
     * 击穿——热点 key 失效瞬间以 setIfAbsent 互斥，仅持锁者回源，其余请求自旋等缓存；
     * 雪崩——成功回源的写入 TTL 经 {@link #jitter} 随机抖动，避免同批 key 同时失效。
     *
     * @param loader 回源逻辑；返回 null 视为"后端无此数据"，触发空值占位
     */
    public <T> Optional<T> getOrLoad(String key, Class<T> clazz, Supplier<T> loader, Duration ttl) {
        Optional<T> cached = get(key, clazz);
        if (cached.isPresent()) {
            return cached;
        }

        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(buildKey(key + LOCK_SUFFIX), "1", LOCK_TTL);
        try {
            if (Boolean.TRUE.equals(locked)) {
                // 双检：排队期间前一个持锁者可能已完成回源
                String raw = readRaw(key);
                if (raw == null) {
                    T value = loader.get();
                    if (value == null) {
                        redisTemplate.opsForValue().set(buildKey(key), NULL_TOMBSTONE, NULL_TTL);
                        log.debug("回源为空，写入空值占位: key={}, ttl={}s", key, NULL_TTL.getSeconds());
                        return Optional.empty();
                    }
                    set(key, value, jitter(ttl));
                    return Optional.of(value);
                }
                return NULL_TOMBSTONE.equals(raw) ? Optional.empty() : get(key, clazz);
            }

            // 未抢到锁：自旋等待持锁者写入（含空值占位场景），超时降级直接回源保可用性
            for (int i = 0; i < LOCK_RETRY_TIMES; i++) {
                Thread.sleep(LOCK_RETRY_INTERVAL_MS);
                if (Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(key)))) {
                    return get(key, clazz);
                }
            }
            log.warn("重建锁竞争超时，降级直接回源: key={}", key);
            return Optional.ofNullable(loader.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.ofNullable(loader.get());
        } finally {
            if (Boolean.TRUE.equals(locked)) {
                redisTemplate.delete(buildKey(key + LOCK_SUFFIX));
            }
        }
    }

    /**
     * TTL 加随机抖动（基准 ±20%，下限 1s），防止同批写入的 key 同时失效引发雪崩
     */
    public Duration jitter(Duration base) {
        long baseMs = Math.max(1000, base.toMillis());
        long jittered = (long) (baseMs * (0.8 + ThreadLocalRandom.current().nextDouble(0.4)));
        return Duration.ofMillis(jittered);
    }

    /**
     * 删除缓存（空值占位一并删除，数据写入后立即纠正）
     */
    public void delete(String key) {
        redisTemplate.delete(buildKey(key));
        log.debug("删除缓存: key={}", key);
    }

    /**
     * 右侧追加到列表（用于消息历史）
     */
    public void listRightPush(String key, String value) {
        redisTemplate.opsForList().rightPush(buildKey(key), value);
    }

    /**
     * 获取列表范围
     */
    public List<String> listRange(String key, long start, long end) {
        return redisTemplate.opsForList().range(buildKey(key), start, end);
    }

    /**
     * 裁剪列表，只保留指定范围
     */
    public void listTrim(String key, long start, long end) {
        redisTemplate.opsForList().trim(buildKey(key), start, end);
    }

    /**
     * 设置过期时间
     */
    public void expire(String key, Duration ttl) {
        redisTemplate.expire(buildKey(key), ttl);
    }

    /**
     * 判断 key 是否存在
     */
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(key)));
    }

    private String readRaw(String key) {
        return redisTemplate.opsForValue().get(buildKey(key));
    }

    private String buildKey(String key) {
        return KEY_PREFIX + key;
    }
}
