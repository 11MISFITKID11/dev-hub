package com.devhub.common;

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
