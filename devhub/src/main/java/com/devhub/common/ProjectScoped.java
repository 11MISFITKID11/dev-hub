package com.devhub.common;

import java.lang.annotation.*;

/**
 * 对象级权限（方案 P1-6，借鉴 Linear 团队边界）：
 * 标注在任务/缺陷写接口上，方法第一个 Long 参数视为对象 ID，
 * 按对象所属项目校验当前用户角色——OWNER 可删改，DEV/QA 可改，非成员 403。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProjectScoped {
    /** 对象类型：TASK / DEFECT / PROJECT / ITERATION / REQUIREMENT */
    ObjType value();

    /** 是否为破坏性操作（仅 OWNER） */
    boolean ownerOnly() default false;

    enum ObjType { TASK, DEFECT, PROJECT, ITERATION, REQUIREMENT }
}
