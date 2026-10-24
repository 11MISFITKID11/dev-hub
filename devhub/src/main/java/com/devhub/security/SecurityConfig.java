package com.devhub.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置（指定选型：Spring Security + JWT，替换原 X-Admin-Token 方案）
 * <p>
 * 规则：登录/静态资源/健康检查放行，其余一律认证；
 * 权限点在 Controller 上用 @PreAuthorize 声明（ai:ops / ai:kb:manage）。
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SSE/异步请求的 ASYNC 分发不再重复鉴权（初始请求已过 JWT；
                        // 拒绝会导致已提交的流连接被中断，前端 reader 异常）
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/assets/**", "/static/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // 通知 SSE：EventSource 带不了头，token 走查询参数由 Controller 校验
                        .requestMatchers("/api/notifications/stream").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(401);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    res.setCharacterEncoding("UTF-8");
                    res.getWriter().write("{\"code\":401,\"message\":\"未登录或令牌已失效\"}");
                }))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // DelegatingPasswordEncoder：兼容 {noop}（骨架种子）与 {bcrypt}（正式）
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }
}
