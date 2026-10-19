package com.devhub.module.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devhub.module.ai.model.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话表 Mapper
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
