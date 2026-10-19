package com.devhub.module.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devhub.module.ai.model.entity.Document;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档表 Mapper
 */
@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
}
