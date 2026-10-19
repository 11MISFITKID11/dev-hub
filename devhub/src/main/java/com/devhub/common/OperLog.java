package com.devhub.common;

import java.lang.annotation.*;

/** 操作日志注解（借鉴 RuoYi @Log）：方法级声明，AOP 落 sys_oper_log */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperLog {
    String module();
    String action();
}
