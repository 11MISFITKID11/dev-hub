package com.devhub.module.ai.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * 对话接口限流（Redis 固定窗口，接口层防护）
 * <p>
 * 与 LlmCallLimiter 分工：这里是入口节流（防 HTTP 洪水刷接口），
 * 信号量是管道内部保护（防 LLM 供应商配额被打爆）。超限直接 429，
 * 不进入昂贵的检索/生成链路。
 */
@Slf4j
@Order(1)
@Component
@RequiredArgsConstructor
public class ChatRateLimitFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redis;

    @Value("${agent.ratelimit.enabled:true}")
    private boolean enabled;

    @Value("${agent.ratelimit.global-per-sec:120}")
    private int globalPerSec;

    @Value("${agent.ratelimit.per-ip-per-sec:15}")
    private int perIpPerSec;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled || !request.getRequestURI().startsWith("/api/v1/chat")) {
            chain.doFilter(request, response);
            return;
        }

        String window = String.valueOf(System.currentTimeMillis() / 1000);
        try {
            if (overLimit("agent:rl:g:" + window, globalPerSec)
                    || overLimit("agent:rl:ip:" + clientIp(request) + ":" + window, perIpPerSec)) {
                log.warn("接口限流触发: uri={}, ip={}", request.getRequestURI(), clientIp(request));
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\"}");
                return;
            }
        } catch (Exception e) {
            // Redis 不可用时放行（限流故障不应阻断服务）
            log.warn("限流计数失败（放行）: {}", e.getMessage());
        }
        chain.doFilter(request, response);
    }

    private boolean overLimit(String key, int limit) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofSeconds(2));
        }
        return count != null && count > limit;
    }

    private String clientIp(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        return (fwd != null && !fwd.isBlank()) ? fwd.split(",")[0].trim() : request.getRemoteAddr();
    }
}
