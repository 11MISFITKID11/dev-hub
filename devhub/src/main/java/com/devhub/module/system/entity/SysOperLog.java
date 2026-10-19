package com.devhub.module.system.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("sys_oper_log")
public class SysOperLog {
    @TableId(type = IdType.AUTO)
    Long id;

    String module;
    String action;
    String method;
    String params;
    String username;
    String ip;
    Integer status;
    String errorMsg;
    Long costMs;
    LocalDateTime createTime;
}
