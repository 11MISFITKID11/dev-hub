package com.devhub.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户加载：sys_user + 角色/权限（sys_role / sys_menu.perm 聚合）
 * <p>
 * 骨架阶段口令用 {noop} 前缀明文存储（DelegatingPasswordEncoder 支持），
 * 正式版改为 BCrypt——见 V1 种子数据注释。
 */
@Service
@RequiredArgsConstructor
public class DbUserDetailsService implements UserDetailsService {

    private final JdbcTemplate jdbc;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        List<User> users = jdbc.query(
                "SELECT id, username, password, status FROM sys_user WHERE username = ? AND deleted = 0",
                (rs, i) -> (User) User.withUsername(rs.getString("username"))
                        .password(rs.getString("password"))
                        .disabled(!"0".equals(rs.getString("status")))
                        .authorities(loadAuthorities(username).toArray(new SimpleGrantedAuthority[0]))
                        .build(),
                username);
        if (users.isEmpty()) {
            throw new UsernameNotFoundException("用户不存在: " + username);
        }
        return users.get(0);
    }

    /** 权限 = 角色标识（ROLE_*) + 菜单权限点（ai:ops 等） */
    public List<SimpleGrantedAuthority> loadAuthorities(String username) {
        return jdbc.query(
                """
                SELECT DISTINCT p FROM (
                  SELECT CONCAT('ROLE_', r.role_key) AS p
                    FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id
                   WHERE ur.username = ?
                  UNION ALL
                  SELECT m.perm AS p
                    FROM sys_user_role ur
                    JOIN sys_role_menu rm ON rm.role_id = ur.role_id
                    JOIN sys_menu m ON m.id = rm.menu_id
                   WHERE ur.username = ? AND m.perm IS NOT NULL AND m.perm != ''
                ) t
                """,
                (rs, i) -> new SimpleGrantedAuthority(rs.getString("p")),
                username, username);
    }

    public Long userIdOf(String username) {
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }
}
