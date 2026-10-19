# -*- coding: utf-8 -*-
"""批量生成 DevHub 宿主业务骨架文件：实体/Mapper/@OperLog 切面/分页配置"""
import os, io

files = {}

ENT = '''package com.devhub.module.{pkg}.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("{table}")
public class {cls} {{
    @TableId(type = IdType.AUTO)
    Long id;

{fields}}}
'''

def ent(pkg, cls, table, fields):
    return ENT.format(pkg=pkg, cls=cls, table=table, fields=fields)

def lg(): return "    @TableLogic\n    Integer deleted;\n"

files['module/system/entity/SysDept.java'] = ent('system','SysDept','sys_dept',
    "    Long parentId;\n    String name;\n    Integer sort;\n\n" + lg())
files['module/system/entity/SysDictData.java'] = ent('system','SysDictData','sys_dict_data',
    "    String type;\n    String label;\n    String value;\n    Integer sort;\n")
files['module/system/entity/SysOperLog.java'] = ent('system','SysOperLog','sys_oper_log',
    "    String module;\n    String action;\n    String method;\n    String params;\n    String username;\n"
    "    String ip;\n    Integer status;\n    String errorMsg;\n    Long costMs;\n    LocalDateTime createTime;\n")
files['module/system/entity/SysLoginLog.java'] = ent('system','SysLoginLog','sys_login_log',
    "    String username;\n    String ip;\n    Integer status;\n    String msg;\n    LocalDateTime createTime;\n")
files['module/project/entity/PrjProject.java'] = ent('project','PrjProject','prj_project',
    "    String name;\n    String code;\n    String ownerUsername;\n    Long deptId;\n    Integer status;\n"
    "    LocalDate startDate;\n    LocalDate endDate;\n    String description;\n\n" + lg() +
    "    LocalDateTime createTime;\n    LocalDateTime updateTime;\n")
files['module/project/entity/PrjIteration.java'] = ent('project','PrjIteration','prj_iteration',
    "    Long projectId;\n    String name;\n    LocalDate startDate;\n    LocalDate endDate;\n    Integer status;\n\n" + lg())
files['module/project/entity/PrjMember.java'] = ent('project','PrjMember','prj_member',
    "    Long projectId;\n    String username;\n    String roleKey;\n")
files['module/task/entity/PrjTask.java'] = ent('task','PrjTask','prj_task',
    "    Long projectId;\n    Long iterationId;\n    String title;\n    String content;\n    String status;\n"
    "    String priority;\n    String assigneeUsername;\n    String creatorUsername;\n    LocalDate dueDate;\n\n" + lg() +
    "    LocalDateTime createTime;\n    LocalDateTime updateTime;\n")
files['module/defect/entity/PrjDefect.java'] = ent('defect','PrjDefect','prj_defect',
    "    Long projectId;\n    Long taskId;\n    String title;\n    String description;\n    String severity;\n"
    "    String status;\n    String reporterUsername;\n    String assigneeUsername;\n\n" + lg() +
    "    LocalDateTime createTime;\n    LocalDateTime updateTime;\n")
files['module/doc/entity/DocCategory.java'] = ent('doc','DocCategory','doc_category',
    "    Long parentId;\n    String name;\n    Integer sort;\n\n" + lg())

MAP = '''package com.devhub.module.{pkg}.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devhub.module.{pkg}.entity.{cls};
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface {cls}Mapper extends BaseMapper<{cls}> {{
}}
'''
for pkg, cls in [('system','SysDept'),('system','SysDictData'),('system','SysOperLog'),('system','SysLoginLog'),
                 ('project','PrjProject'),('project','PrjIteration'),('project','PrjMember'),
                 ('task','PrjTask'),('defect','PrjDefect'),('doc','DocCategory')]:
    files['module/%s/mapper/%sMapper.java' % (pkg, cls)] = MAP.format(pkg=pkg, cls=cls)

files['common/OperLog.java'] = '''package com.devhub.common;

import java.lang.annotation.*;

/** 操作日志注解（借鉴 RuoYi @Log）：方法级声明，AOP 落 sys_oper_log */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperLog {
    String module();
    String action();
}
'''

files['common/OperLogAspect.java'] = '''package com.devhub.common;

import com.devhub.module.system.entity.SysOperLog;
import com.devhub.module.system.mapper.SysOperLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

/** 操作日志切面：记录 模块/动作/参数/人/IP/耗时/成败（RuoYi 同款模式） */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperLogAspect {

    private final SysOperLogMapper operLogMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Around("@annotation(operLog)")
    public Object around(ProceedingJoinPoint pjp, OperLog operLog) throws Throwable {
        long start = System.currentTimeMillis();
        SysOperLog entry = new SysOperLog();
        entry.setModule(operLog.module());
        entry.setAction(operLog.action());
        entry.setMethod(pjp.getSignature().getDeclaringTypeName() + "." + pjp.getSignature().getName());
        entry.setUsername(currentUser());
        entry.setIp(currentIp());
        try {
            entry.setParams(truncate(paramsJson(pjp.getArgs())));
            Object result = pjp.proceed();
            entry.setStatus(1);
            entry.setCostMs(System.currentTimeMillis() - start);
            save(entry);
            return result;
        } catch (Throwable e) {
            entry.setStatus(0);
            entry.setErrorMsg(truncate(String.valueOf(e.getMessage())));
            entry.setCostMs(System.currentTimeMillis() - start);
            save(entry);
            throw e;
        }
    }

    @Async
    void save(SysOperLog entry) {
        try { operLogMapper.insert(entry); } catch (Exception e) { log.warn("操作日志落库失败: {}", e.getMessage()); }
    }

    private String paramsJson(Object[] args) {
        try {
            Object[] safe = java.util.Arrays.stream(args)
                    .map(a -> a instanceof MultipartFile ? "((file:" + ((MultipartFile) a).getOriginalFilename() + "))" : a)
                    .toArray();
            return objectMapper.writeValueAsString(safe);
        } catch (Exception e) { return "params-serialize-error"; }
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : null;
    }

    private String currentIp() {
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return null;
        HttpServletRequest req = attrs.getRequest();
        String fwd = req.getHeader("X-Forwarded-For");
        return fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : req.getRemoteAddr();
    }

    private String truncate(String s) { return s == null ? null : (s.length() > 1900 ? s.substring(0, 1900) : s); }
}
'''

files['config/MybatisPlusConfig.java'] = '''package com.devhub.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
'''

base = os.path.join(r'D:\kaifa\javaproject\devhub\src\main\java\com\devhub')
for rel, content in files.items():
    p = os.path.join(base, rel.replace('/', os.sep))
    os.makedirs(os.path.dirname(p), exist_ok=True)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(content)
print('%d files generated' % len(files))
