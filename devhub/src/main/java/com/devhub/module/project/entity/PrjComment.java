package com.devhub.module.project.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("prj_comment")
public class PrjComment {
    @TableId(type = IdType.AUTO)
    Long id;
    String objType;
    Long objId;
    String content;
    String creator;
    LocalDateTime createTime;
}
