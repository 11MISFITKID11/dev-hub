package com.devhub.module.system.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("sys_login_log")
public class SysLoginLog {
    @TableId(type = IdType.AUTO)
    Long id;

    String username;
    String ip;
    Integer status;
    String msg;
    LocalDateTime createTime;
}
