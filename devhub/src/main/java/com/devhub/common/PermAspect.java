package com.devhub.common;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限切面：@RequirePerm 方法执行前，按当前用户角色查 sys_role_perm；
 * 任一角色持有权限点即放行。5 分钟进程缓存 + 变更靠 TTL 过期。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class PermAspect {

    private final JdbcTemplate jdbc;
    private volatile Map<String, List<String>> cache = Map.of();
    private volatile long cachedAt = 0;

    @Before("@annotation(requirePerm)")
    public void check(RequirePerm requirePerm) {
        String perm = requirePerm.value();
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) throw new AccessDeniedException("未认证");
        List<String> roles = auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .filter(r -> !r.contains(":")) // 权限串不是角色
                .toList();
        for (String role : rolePerms().keySet()) {
            if (roles.contains(role) && rolePerms().get(role).contains(perm)) return;
        }
        throw new AccessDeniedException("缺少权限点: " + perm);
    }

    private Map<String, List<String>> rolePerms() {
        long now = System.currentTimeMillis();
        if (cache.isEmpty() || now - cachedAt > 300_000) {
            Map<String, List<String>> m = new ConcurrentHashMap<>();
            jdbc.query("SELECT r.role_key AS rk, p.perm FROM sys_role_perm p JOIN sys_role r ON r.id = p.role_id",
                    rs -> {
                        m.computeIfAbsent(rs.getString("rk"), k -> new java.util.ArrayList<>())
                                .add(rs.getString("perm"));
                    });
            cache = m;
            cachedAt = now;
        }
        return cache;
    }
}
