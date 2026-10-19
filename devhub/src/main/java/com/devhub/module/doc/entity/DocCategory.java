package com.devhub.module.doc.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("doc_category")
public class DocCategory {
    @TableId(type = IdType.AUTO)
    Long id;

    Long parentId;
    String name;
    Integer sort;

    @TableLogic
    Integer deleted;
}
