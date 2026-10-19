package com.devhub.module.system.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("sys_dict_data")
public class SysDictData {
    @TableId(type = IdType.AUTO)
    Long id;

    String type;
    String label;
    String value;
    Integer sort;
}
