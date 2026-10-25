package com.devhub.module.ai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Prompt 模板覆盖（scene 唯一；无行 = 使用代码内置默认） */
@Data
@TableName("prompt_template")
public class PromptTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String scene;
    private String name;
    private String content;
    private String updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
