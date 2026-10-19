package com.devhub.module.project.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("prj_activity")
public class PrjActivity {
    @TableId(type = IdType.AUTO)
    Long id;
    String objType;
    Long objId;
    String action;
    String fieldName;
    String oldValue;
    String newValue;
    String actor;
    LocalDateTime createTime;
}
