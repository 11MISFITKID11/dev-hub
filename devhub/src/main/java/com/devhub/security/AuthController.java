package com.devhub.security;

import com.devhub.module.ai.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 认证接口：登录换 JWT、当前用户信息（含菜单）、登出（客户端弃用令牌）
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final AuthenticationManagerHolder holder;
    private final JwtTokenProvider tokenProvider;
    private final DbUserDetailsService userDetailsService;
    private final JdbcTemplate jdbc;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    /**
     * 客户自助注册（用户侧能力）：默认 USER 角色，仅见智能问答/我的工单/知识库。
     * 注册成功直接返回令牌，免二次登录。
     */
    @PostMapping("/register")
    public Result<Map<String, Object>> register(@Valid @RequestBody LoginRequest req) {
        if (req.getUsername().length() < 3 || req.getUsername().length() > 32
                || !req.getUsername().matches("[a-zA-Z0-9_]+")) {
            return Result.fail(400, "用户名需为 3-32 位字母/数字/下划线");
        }
        if (req.getPassword().length() < 6) {
            return Result.fail(400, "密码至少 6 位");
        }
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username = ?", Integer.class, req.getUsername());
        if (exists != null && exists > 0) {
            return Result.fail(409, "用户名已存在");
        }
        jdbc.update("INSERT INTO sys_user (username, password, nickname) VALUES (?,?,?)",
                req.getUsername(), passwordEncoder.encode(req.getPassword()), req.getUsername());
        jdbc.update("INSERT INTO sys_user_role (username, role_id) "
                + "SELECT ?, id FROM sys_role WHERE role_key = 'USER'", req.getUsername());
        loginLog(req.getUsername(), "-", 1, "注册成功");
        String token = tokenProvider.createToken(
                userDetailsService.userIdOf(req.getUsername()), req.getUsername(), List.of("USER"));
        return Result.success(Map.of(
                "token", token,
                "username", req.getUsername(),
                "menus", menusOf(req.getUsername())));
    }

    @Data
    public static class LoginRequest {
        @NotBlank(message = "用户名不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest req,
                                             jakarta.servlet.http.HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        try {
            Authentication auth = holder.authenticationManager().authenticate(
                    new UsernamePasswordAuthenticationToken(req.getUsername(), req.getPassword()));
            var user = (org.springframework.security.core.userdetails.User) auth.getPrincipal();
            List<String> roles = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a.startsWith("ROLE_"))
                    .map(a -> a.substring(5))
                    .toList();
            String token = tokenProvider.createToken(
                    userDetailsService.userIdOf(req.getUsername()), req.getUsername(), roles);
            loginLog(req.getUsername(), ip, 1, "登录成功");
            return Result.success(Map.of(
                    "token", token,
                    "username", req.getUsername(),
                    "menus", menusOf(req.getUsername())));
        } catch (AuthenticationException e) {
            loginLog(req.getUsername(), ip, 0, "用户名或密码错误");
            return Result.fail(401, "用户名或密码错误");
        }
    }

    private void loginLog(String username, String ip, int status, String msg) {
        try {
            jdbc.update("INSERT INTO sys_login_log (username, ip, status, msg) VALUES (?,?,?,?)",
                    username, ip, status, msg);
        } catch (Exception ignored) { }
    }

    /** 当前登录用户：信息 + 角色 + 权限点 + 可见菜单（前端骨架侧栏渲染用） */
    @GetMapping("/me")
    public Result<Map<String, Object>> me(Authentication auth) {
        String username = auth.getName();
        Map<String, Object> data = new java.util.LinkedHashMap<>(Map.of(
                "username", username,
                "roles", auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList(),
                "menus", menusOf(username)));
        try {
            data.put("perms", jdbc.queryForList("""
                    SELECT DISTINCT p.perm FROM sys_role_perm p
                    JOIN sys_user_role ur ON ur.role_id = p.role_id
                    WHERE ur.username = ?""", String.class, username));
        } catch (Exception ignored) { }
        return Result.success(data);
    }

    /** 登出 = 客户端删除令牌（无服务端黑名单，骨架版；正式版可加 Redis 黑名单） */
    @PostMapping("/logout")
    public Result<String> logout() {
        return Result.success("已登出");
    }

    /** 密钥轮换（仅管理员）：旧密钥降级为 previous，10 分钟宽限期内旧 token 仍可用 */
    @PostMapping("/rotate-secret")
    public Result<Void> rotateSecret(@org.springframework.web.bind.annotation.RequestBody java.util.Map<String, String> body) {
        requireAdmin();
        String secret = body.get("secret");
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            return Result.fail(400, "新密钥至少 32 字节");
        }
        tokenProvider.rotate(secret);
        return Result.success(null);
    }

    /** 全员强制下线（仅管理员）：此前签发的 token 全部 401 */
    @PostMapping("/logout-all")
    public Result<Void> logoutAll() {
        requireAdmin();
        tokenProvider.forceLogoutAll();
        return Result.success(null);
    }

    private void requireAdmin() {
        boolean admin = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().contains("ADMIN"));
        if (!admin) throw new org.springframework.security.access.AccessDeniedException("仅管理员");
    }

    private List<Map<String, Object>> menusOf(String username) {
        return jdbc.queryForList("""
                SELECT DISTINCT m.id, m.parent_id, m.name, m.path, m.sort
                  FROM sys_user_role ur
                  JOIN sys_role_menu rm ON rm.role_id = ur.role_id
                  JOIN sys_menu m ON m.id = rm.menu_id
                 WHERE ur.username = ? AND m.visible = 1
                 ORDER BY m.sort
                """, username);
    }

    /** 延迟取 AuthenticationManager（避免循环依赖） */
    @org.springframework.stereotype.Component
    @RequiredArgsConstructor
    public static class AuthenticationManagerHolder {
        private final AuthenticationManager manager;
        public AuthenticationManager authenticationManager() {
            return manager;
        }
    }
}
