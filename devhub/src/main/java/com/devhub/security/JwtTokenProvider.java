package com.devhub.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JWT 工具：签发与校验（HS256）+ 双密钥轮换 + 全局强制下线。
 * <p>轮换：Redis 存 current/previous 两把密钥（无 Redis 覆盖时回落环境变量基线），
 * 签发永远用 current，校验先 current 后 previous（宽限期默认 10 分钟，超期旧 token 401）。
 * <p>强制下线：auth:jwt:min-iat 记录全局最早可接受签发时间，早于它的 token 一律失效。
 */
@Slf4j
@Component
public class JwtTokenProvider {

    public static final String K_CURRENT = "auth:jwt:current";
    public static final String K_PREVIOUS = "auth:jwt:previous";
    public static final String K_ROTATED_AT = "auth:jwt:rotated-at";
    public static final String K_MIN_IAT = "auth:jwt:min-iat";
    private static final long GRACE_MILLIS = 10 * 60_000L;

    private final SecretKey baseKey;
    private final long ttlMillis;
    private final StringRedisTemplate redis;
    /** 轮换后的密钥缓存（进程内，避免每 token 两次 Redis 往返） */
    private final AtomicReference<CachedKeys> cached = new AtomicReference<>();

    public JwtTokenProvider(@Value("${devhub.jwt.secret:devhub-local-dev-only-secret-0123456789abcdef}") String secret,
                            @Value("${devhub.jwt.ttl-hours:24}") long ttlHours,
                            StringRedisTemplate redis) {
        this.baseKey = toKey(secret);
        this.ttlMillis = ttlHours * 3600_000L;
        this.redis = redis;
    }

    private record CachedKeys(SecretKey current, SecretKey previous, long rotatedAt, long loadedAt) { }

    private static SecretKey toKey(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            bytes = padded;
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    /** 双密钥视图：Redis 覆盖优先，60s 进程内缓存 */
    private CachedKeys keys() {
        CachedKeys c = cached.get();
        if (c != null && System.currentTimeMillis() - c.loadedAt() < 60_000) return c;
        try {
            String cur = redis.opsForValue().get(K_CURRENT);
            String prev = redis.opsForValue().get(K_PREVIOUS);
            String rot = redis.opsForValue().get(K_ROTATED_AT);
            if (cur != null) {
                c = new CachedKeys(toKey(cur), prev != null ? toKey(prev) : null,
                        rot != null ? Long.parseLong(rot) : 0, System.currentTimeMillis());
                cached.set(c);
                return c;
            }
        } catch (Exception e) {
            log.debug("轮换密钥读取失败，回落环境变量基线: {}", e.getMessage());
        }
        return new CachedKeys(baseKey, null, 0, System.currentTimeMillis());
    }

    /** 轮换：当前密钥降级为 previous，新密钥成为 current（仅管理员入口调用） */
    public void rotate(String newSecret) {
        String cur = null;
        try { cur = redis.opsForValue().get(K_CURRENT); } catch (Exception ignored) { }
        try {
            long now = System.currentTimeMillis();
            if (cur != null) redis.opsForValue().set(K_PREVIOUS, cur);
            redis.opsForValue().set(K_CURRENT, newSecret);
            redis.opsForValue().set(K_ROTATED_AT, String.valueOf(now));
            cached.set(new CachedKeys(toKey(newSecret), cur != null ? toKey(cur) : baseKey, now, now));
            log.info("JWT 密钥已轮换，旧密钥宽限期 {} 分钟", GRACE_MILLIS / 60000);
        } catch (Exception e) {
            throw new IllegalStateException("密钥轮换需要 Redis 可用: " + e.getMessage());
        }
    }

    /** 全局强制下线：此后签发时间早于此刻的 token 全部失效 */
    public void forceLogoutAll() {
        redis.opsForValue().set(K_MIN_IAT, String.valueOf(System.currentTimeMillis()));
        log.info("已触发全员强制下线");
    }

    public String createToken(Long userId, String username, List<String> roles) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("roles", roles)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(keys().current())
                .compact();
    }

    /** 校验并解析：current 优先，previous 宽限期内兜底；全局 min-iat 强制下线检查 */
    public Claims parse(String token) {
        CachedKeys k = keys();
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(k.current()).build().parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            if (k.previous() == null
                    || System.currentTimeMillis() - k.rotatedAt() > GRACE_MILLIS) throw e;
            claims = Jwts.parser().verifyWith(k.previous()).build().parseSignedClaims(token).getPayload();
        }
        try {
            String minIat = redis.opsForValue().get(K_MIN_IAT);
            if (minIat != null && claims.getIssuedAt() != null
                    && claims.getIssuedAt().getTime() < Long.parseLong(minIat)) {
                throw new io.jsonwebtoken.JwtException("token 已被全局强制下线");
            }
        } catch (NumberFormatException ignored) { }
        return claims;
    }
}
