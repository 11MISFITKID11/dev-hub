package com.devhub.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 启动安全自检：密钥不达标直接拒绝启动（fail-fast）。
 * <p>规则：长度 ≥ 32 字节，且不等于历史弱默认值（历史默认值见 git 记录，此处硬编码拦截）。
 * 仅在 prod profile 生效之外也做被动检查——弱密钥只告警，无密钥（占位符未替换）直接失败。
 */
@Slf4j
@Component
public class SecurityStartupCheck {

    private static final String LEGACY_WEAK_SECRET = "devhub-skeleton-secret-key-please-change-in-prod-32bytes";

    @Value("${devhub.jwt.secret:}")
    private String jwtSecret;

    @PostConstruct
    void check() {
        if (jwtSecret == null || jwtSecret.isBlank() || jwtSecret.startsWith("${")) {
            throw new IllegalStateException(
                    "[安全自检失败] DEVHUB_JWT_SECRET 未设置（生产 profile 无默认值）。请通过环境变量注入 ≥32 字节随机密钥后重启。");
        }
        if (LEGACY_WEAK_SECRET.equals(jwtSecret) || jwtSecret.length() < 32) {
            throw new IllegalStateException(
                    "[安全自检失败] JWT 密钥弱于安全基线（≥32 字节且非历史默认值），拒绝启动。");
        }
        log.info("[安全自检] JWT 密钥基线校验通过");
    }
}
