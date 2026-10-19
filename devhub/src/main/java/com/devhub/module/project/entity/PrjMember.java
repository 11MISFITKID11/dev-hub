package com.devhub.module.project.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("prj_member")
public class PrjMember {
    @TableId(type = IdType.AUTO)
    Long id;

    Long projectId;
    String username;
    String roleKey;
}
