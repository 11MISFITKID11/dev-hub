package com.devhub.module.system.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("sys_dept")
public class SysDept {
    @TableId(type = IdType.AUTO)
    Long id;

    Long parentId;
    String name;
    Integer sort;

    @TableLogic
    Integer deleted;
}
