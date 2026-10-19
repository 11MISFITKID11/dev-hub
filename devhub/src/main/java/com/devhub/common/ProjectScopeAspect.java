package com.devhub.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devhub.module.ai.common.Result;
import com.devhub.module.defect.entity.PrjDefect;
import com.devhub.module.defect.mapper.PrjDefectMapper;
import com.devhub.module.project.entity.PrjMember;
import com.devhub.module.project.mapper.PrjMemberMapper;
import com.devhub.module.task.entity.PrjTask;
import com.devhub.module.task.mapper.PrjTaskMapper;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 对象级权限切面：@ProjectScoped 注解驱动（方案 P1-6）
 * <p>
 * 规则：ADMIN 全通过；项目 OWNER 通过一切；成员（DEV/QA）通过非 ownerOnly 操作；
 * 非项目成员 403（借鉴 Linear：项目即权限边界）。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class ProjectScopeAspect {

    private final PrjTaskMapper taskMapper;
    private final PrjDefectMapper defectMapper;
    private final PrjMemberMapper memberMapper;
    private final com.devhub.module.project.mapper.PrjIterationMapper iterationMapper;
    private final com.devhub.module.project.mapper.PrjRequirementMapper requirementMapper;
    private final JdbcTemplate jdbc;

    @Around("@annotation(scope)")
    public Object around(ProceedingJoinPoint pjp, ProjectScoped scope) throws Throwable {
        String me = currentUser();
        if (isAdmin(me)) return pjp.proceed();

        Long objId = firstLong(pjp.getArgs());
        if (objId == null) return pjp.proceed();

        Long projectId = projectIdOf(scope.value(), objId);
        if (projectId == null) return pjp.proceed(); // 对象不存在等交由后续逻辑处理

        String err = checkMemberWrite(me, projectId, scope.ownerOnly());
        return err != null ? Result.fail(403, err) : pjp.proceed();
    }

    /**
     * 手工校验入口（供无法挂注解的接口复用，如评论的 objType 为运行时字符串）。
     * @return null=通过；否则为拒绝原因
     */
    public String checkMemberWrite(String username, Long projectId, boolean ownerOnly) {
        if (isAdmin(username)) return null;
        List<PrjMember> memberships = memberMapper.selectList(
                new LambdaQueryWrapper<PrjMember>().eq(PrjMember::getProjectId, projectId));
        PrjMember mine = memberships.stream()
                .filter(m -> username.equals(m.getUsername())).findFirst().orElse(null);
        if (mine == null) return "非项目成员，无权操作该项目下的对象";
        if (ownerOnly && !"OWNER".equals(mine.getRoleKey())) {
            return "该操作仅项目负责人（OWNER）可执行";
        }
        return null;
    }

    private Long projectIdOf(ProjectScoped.ObjType type, Long objId) {
        return switch (type) {
            case TASK -> {
                PrjTask t = taskMapper.selectById(objId);
                yield t == null ? null : t.getProjectId();
            }
            case DEFECT -> {
                PrjDefect d = defectMapper.selectById(objId);
                yield d == null ? null : d.getProjectId();
            }
            case ITERATION -> {
                var it = iterationMapper.selectById(objId);
                yield it == null ? null : it.getProjectId();
            }
            case REQUIREMENT -> {
                var r = requirementMapper.selectById(objId);
                yield r == null ? null : r.getProjectId();
            }
            case PROJECT -> objId;
        };
    }

    private Long firstLong(Object[] args) {
        for (Object a : args) {
            if (a instanceof Long l) return l;
        }
        return null;
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : null;
    }

    private boolean isAdmin(String username) {
        if (username == null) return false;
        try {
            Long c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id " +
                    "WHERE ur.username = ? AND r.role_key = 'ADMIN'", Long.class, username);
            return c != null && c > 0;
        } catch (Exception e) { return false; }
    }
}
