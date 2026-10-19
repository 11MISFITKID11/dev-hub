package com.devhub.module.project.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("prj_attachment")
public class PrjAttachment {
    @TableId(type = IdType.AUTO)
    Long id;
    String objType;
    Long objId;
    String fileName;
    Long fileSize;
    String contentType;
    String storedPath;
    String uploader;

    @TableLogic
    Integer deleted;
    LocalDateTime createTime;
}
