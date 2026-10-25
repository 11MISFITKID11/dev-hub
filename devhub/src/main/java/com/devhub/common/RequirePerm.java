package com.devhub.common;

import java.lang.annotation.*;

/** 接口级权限点：切面按角色-权限表校验，403 拒绝（后端是最终防线，前端按钮隐藏仅为体验） */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePerm {
    String value();
}
