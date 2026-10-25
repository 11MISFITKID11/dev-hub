package com.devhub.module.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devhub.module.ai.model.entity.PromptTemplate;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplate> {
}
