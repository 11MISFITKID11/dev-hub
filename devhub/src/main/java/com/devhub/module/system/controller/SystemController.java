package com.devhub.module.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.devhub.common.OperLog;
import com.devhub.module.ai.common.Result;
import com.devhub.module.system.entity.SysDept;
import com.devhub.module.system.entity.SysDictData;
import com.devhub.module.system.entity.SysLoginLog;
import com.devhub.module.system.entity.SysOperLog;
import com.devhub.module.system.mapper.SysDeptMapper;
import com.devhub.module.system.mapper.SysDictDataMapper;
import com.devhub.module.system.mapper.SysLoginLogMapper;
import com.devhub.module.system.mapper.SysOperLogMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 系统管理：部门树 / 字典 / 操作日志 / 登录日志 / 用户管理 / 个人中心（改密）
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
@Validated
public class SystemController {

    private final SysDeptMapper deptMapper;
    private final SysDictDataMapper dictMapper;
    private final SysOperLogMapper operLogMapper;
    private final SysLoginLogMapper loginLogMapper;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    // ---------- 部门树 ----------
    @GetMapping("/depts")
    public Result<List<Map<String, Object>>> deptTree() {
        List<SysDept> all = deptMapper.selectList(
                new LambdaQueryWrapper<SysDept>().orderByAsc(SysDept::getSort));
        Map<Long, List<Map<String, Object>>> byParent = new LinkedHashMap<>();
        for (SysDept d : all) {
            byParent.computeIfAbsent(d.getParentId() == null ? 0L : d.getParentId(), k -> new ArrayList<>())
                    .add(node(d));
        }
        return Result.success(buildTree(byParent, 0L));
    }

    private Map<String, Object> node(SysDept d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("name", d.getName());
        m.put("sort", d.getSort());
        return m;
    }

    private List<Map<String, Object>> buildTree(Map<Long, List<Map<String, Object>>> byParent, Long parent) {
        List<Map<String, Object>> kids = byParent.getOrDefault(parent, new ArrayList<>());
        for (Map<String, Object> kid : kids) {
            kid.put("children", buildTree(byParent, (Long) kid.get("id")));
        }
        return kids;
    }

    @OperLog(module = "系统管理", action = "新建部门")
    @PreAuthorize("hasAuthority('system:manage')")
    @PostMapping("/depts")
    public Result<SysDept> createDept(@RequestBody SysDept dept) {
        deptMapper.insert(dept);
        return Result.success(dept);
    }

    @OperLog(module = "系统管理", action = "删除部门")
    @PreAuthorize("hasAuthority('system:manage')")
    @DeleteMapping("/depts/{id}")
    public Result<String> deleteDept(@PathVariable Long id) {
        Long users = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE dept_id = ? AND deleted = 0", Long.class, id);
        if (users != null && users > 0) return Result.fail(400, "部门下仍有用户");
        Long sub = deptMapper.selectCount(new LambdaQueryWrapper<SysDept>().eq(SysDept::getParentId, id));
        if (sub != null && sub > 0) return Result.fail(400, "存在子部门，先删子部门");
        deptMapper.deleteById(id);
        return Result.success("ok");
    }

    // ---------- 字典 ----------
    @GetMapping("/dicts")
    public Result<Map<String, List<Map<String, Object>>>> dicts() {
        List<SysDictData> all = dictMapper.selectList(
                new LambdaQueryWrapper<SysDictData>().orderByAsc(SysDictData::getSort));
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (SysDictData d : all) {
            grouped.computeIfAbsent(d.getType(), k -> new ArrayList<>())
                    .add(Map.of("label", d.getLabel(), "value", d.getValue()));
        }
        return Result.success(grouped);
    }

    /** 字典明细（含 id，管理维护用） */
    @GetMapping("/dicts/list")
    @PreAuthorize("hasAuthority('system:manage')")
    public Result<List<SysDictData>> dictList(@RequestParam(required = false) String type) {
        return Result.success(dictMapper.selectList(new LambdaQueryWrapper<SysDictData>()
                .eq(type != null && !type.isBlank(), SysDictData::getType, type)
                .orderByAsc(SysDictData::getType).orderByAsc(SysDictData::getSort)));
    }

    /** 新增字典项（工单分类等业务字典维护入口） */
    @OperLog(module = "系统管理", action = "新增字典")
    @PostMapping("/dicts")
    @PreAuthorize("hasAuthority('system:manage')")
    public Result<SysDictData> addDict(@RequestBody DictBody body) {
        if (body.getType() == null || body.getLabel() == null || body.getValue() == null) {
            return Result.fail(400, "type/label/value 不能为空");
        }
        Long cnt = dictMapper.selectCount(new LambdaQueryWrapper<SysDictData>()
                .eq(SysDictData::getType, body.getType()).eq(SysDictData::getValue, body.getValue()));
        if (cnt != null && cnt > 0) return Result.fail(409, "该类型下已存在相同取值: " + body.getValue());
        SysDictData d = new SysDictData();
        d.setType(body.getType().trim());
        d.setLabel(body.getLabel().trim());
        d.setValue(body.getValue().trim());
        d.setSort(body.getSort() == null ? 99 : body.getSort());
        dictMapper.insert(d);
        return Result.success(d);
    }

    @OperLog(module = "系统管理", action = "修改字典")
    @PutMapping("/dicts/{id}")
    @PreAuthorize("hasAuthority('system:manage')")
    public Result<String> updateDict(@PathVariable Long id, @RequestBody DictBody body) {
        SysDictData d = dictMapper.selectById(id);
        if (d == null) return Result.fail(404, "字典项不存在");
        if (body.getLabel() != null) d.setLabel(body.getLabel().trim());
        if (body.getSort() != null) d.setSort(body.getSort());
        dictMapper.updateById(d);
        return Result.success("已保存");
    }

    @OperLog(module = "系统管理", action = "删除字典")
    @DeleteMapping("/dicts/{id}")
    @PreAuthorize("hasAuthority('system:manage')")
    public Result<String> delDict(@PathVariable Long id) {
        dictMapper.deleteById(id);
        return Result.success("已删除");
    }

    @Data
    public static class DictBody {
        private String type;
        private String label;
        private String value;
        private Integer sort;
    }

    // ---------- 个人资料 ----------
    @GetMapping("/profile")
    public Result<Map<String, Object>> profile() {
        String me = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT username, nickname, create_time FROM sys_user WHERE username = ? AND deleted = 0", me);
        List<String> roles = jdbc.queryForList(
                "SELECT r.role_key FROM sys_role r JOIN sys_user_role ur ON ur.role_id = r.id WHERE ur.username = ?",
                String.class, me);
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("username", row.get("username"));
        vo.put("nickname", row.get("nickname"));
        vo.put("createTime", row.get("create_time"));
        vo.put("roles", roles);
        return Result.success(vo);
    }

    @OperLog(module = "个人中心", action = "修改昵称")
    @PutMapping("/profile")
    public Result<String> updateNickname(@RequestBody Map<String, String> body) {
        String me = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        String nickname = body.get("nickname");
        if (nickname != null && nickname.length() > 32) return Result.fail(400, "昵称过长（≤32 字）");
        jdbc.update("UPDATE sys_user SET nickname = ? WHERE username = ?", nickname, me);
        return Result.success("已保存");
    }

    // ---------- 操作日志 / 登录日志 ----------
    @PreAuthorize("hasAuthority('system:manage')")
    @GetMapping("/logs/oper")
    public Result<Page<SysOperLog>> operLogs(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "10") long size) {
        return Result.success(operLogMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<SysOperLog>().orderByDesc(SysOperLog::getId)));
    }

    @PreAuthorize("hasAuthority('system:manage')")
    @GetMapping("/logs/login")
    public Result<Page<SysLoginLog>> loginLogs(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "10") long size) {
        return Result.success(loginLogMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<SysLoginLog>().orderByDesc(SysLoginLog::getId)));
    }

    // ---------- 用户管理 ----------
    @PreAuthorize("hasAuthority('system:manage')")
    @GetMapping("/users")
    public Result<Page<Map<String, Object>>> users(@RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "10") long size,
                                                   @RequestParam(required = false) String keyword) {
        String where = keyword == null || keyword.isBlank() ? "WHERE u.deleted = 0"
                : "WHERE u.username LIKE " + quote('%' + keyword + '%') + " AND u.deleted = 0";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user u " + where, Long.class);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT u.id, u.username, u.nickname, u.dept_id, u.status, u.create_time, " +
                "  d.name AS dept_name, GROUP_CONCAT(r.role_key) AS roles " +
                "FROM sys_user u LEFT JOIN sys_dept d ON d.id = u.dept_id " +
                "LEFT JOIN sys_user_role ur ON ur.username = u.username " +
                "LEFT JOIN sys_role r ON r.id = ur.role_id " +
                where +
                " GROUP BY u.id ORDER BY u.id LIMIT " + ((page - 1) * size) + "," + size);
        return Result.success(new Page<Map<String, Object>>(page, size).setRecords(rows).setTotal(total));
    }

    /** 所有用户名选项（任务指派/成员添加下拉） */
    @GetMapping("/users/options")
    public Result<List<Map<String, Object>>> userOptions() {
        return Result.success(jdbc.queryForList(
                "SELECT username, nickname FROM sys_user WHERE deleted = 0 AND status = '0'"));
    }

    @Data
    public static class CreateUserRequest {
        @NotBlank private String username;
        @NotBlank private String password;
        private String nickname;
        private Long deptId;
        private String roleKey;
    }

    @OperLog(module = "系统管理", action = "创建用户")
    @PreAuthorize("hasAuthority('system:manage')")
    @PostMapping("/users")
    public Result<String> createUser(@jakarta.validation.Valid @RequestBody CreateUserRequest req) {
        Long exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username = ?", Long.class, req.getUsername());
        if (exists != null && exists > 0) return Result.fail(400, "用户名已存在");
        jdbc.update("INSERT INTO sys_user (username, password, nickname, dept_id) VALUES (?,?,?,?)",
                req.getUsername(), passwordEncoder.encode(req.getPassword()),
                req.getNickname(), req.getDeptId());
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM sys_role WHERE role_key = ?", Long.class,
                req.getRoleKey() == null ? "USER" : req.getRoleKey());
        jdbc.update("INSERT INTO sys_user_role (username, role_id) VALUES (?,?)", req.getUsername(), roleId);
        return Result.success("ok");
    }

    @OperLog(module = "系统管理", action = "重置密码")
    @PreAuthorize("hasAuthority('system:manage')")
    @PostMapping("/users/{username}/resetPwd")
    public Result<String> resetPwd(@PathVariable String username, @RequestBody Map<String, String> body) {
        String pwd = body.getOrDefault("password", "");
        if (pwd.length() < 6) return Result.fail(400, "密码至少 6 位");
        jdbc.update("UPDATE sys_user SET password = ? WHERE username = ?",
                passwordEncoder.encode(pwd), username);
        return Result.success("ok");
    }

    @OperLog(module = "系统管理", action = "删除用户")
    @PreAuthorize("hasAuthority('system:manage')")
    @DeleteMapping("/users/{username}")
    public Result<String> deleteUser(@PathVariable String username) {
        if ("admin".equals(username)) return Result.fail(400, "内置管理员不可删除");
        jdbc.update("UPDATE sys_user SET deleted = 1 WHERE username = ?", username);
        return Result.success("ok");
    }

    // ---------- 角色管理（交互抄 RuoYi） ----------
    @PreAuthorize("hasAuthority('system:manage')")
    @GetMapping("/roles")
    public Result<List<Map<String, Object>>> roles() {
        return Result.success(jdbc.queryForList(
                "SELECT r.id, r.role_key, r.name, COUNT(DISTINCT rm.menu_id) AS menu_count " +
                "FROM sys_role r LEFT JOIN sys_role_menu rm ON rm.role_id = r.id " +
                "WHERE r.deleted = 0 GROUP BY r.id, r.role_key, r.name"));
    }

    /** 角色当前菜单权限（勾选回显） */
    @PreAuthorize("hasAuthority('system:manage')")
    @GetMapping("/roles/{id}/menus")
    public Result<List<Long>> roleMenus(@PathVariable Long id) {
        return Result.success(jdbc.queryForList(
                "SELECT menu_id FROM sys_role_menu WHERE role_id = ?", Long.class, id));
    }

    @OperLog(module = "系统管理", action = "保存角色权限")
    @PreAuthorize("hasAuthority('system:manage')")
    @PutMapping("/roles/{id}/menus")
    public Result<String> assignMenus(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        List<Long> menuIds = body.getOrDefault("menuIds", List.of());
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id = ?", id);
        for (Long mid : menuIds) jdbc.update("INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (?,?)", id, mid);
        return Result.success("已更新 " + menuIds.size() + " 项权限");
    }

    @PreAuthorize("hasAuthority('system:manage')")
    @GetMapping("/menus/all")
    public Result<List<Map<String, Object>>> allMenus() {
        return Result.success(jdbc.queryForList("SELECT id, parent_id, name, perm FROM sys_menu WHERE visible = 1 ORDER BY sort"));
    }

    // ---------- 个人中心：改密 ----------
    @Data
    public static class ChangePwdRequest {
        @NotBlank private String oldPassword;
        @NotBlank private String newPassword;
    }

    @OperLog(module = "个人中心", action = "修改密码")
    @PostMapping("/profile/password")
    public Result<String> changePassword(@jakarta.validation.Valid @RequestBody ChangePwdRequest req) {
        String me = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        String stored = jdbc.queryForObject(
                "SELECT password FROM sys_user WHERE username = ? AND deleted = 0", String.class, me);
        if (!passwordEncoder.matches(req.getOldPassword(), stored)) {
            return Result.fail(400, "原密码不正确");
        }
        if (req.getNewPassword().length() < 6) return Result.fail(400, "新密码至少 6 位");
        jdbc.update("UPDATE sys_user SET password = ? WHERE username = ?",
                passwordEncoder.encode(req.getNewPassword()), me);
        return Result.success("密码已修改，请重新登录");
    }

    private String quote(String s) { return "'" + s.replace("'", "''") + "'"; }
}
