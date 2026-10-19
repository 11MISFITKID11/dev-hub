package com.devhub.module.ai.mapper;

import com.devhub.module.ai.model.entity.Message;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 消息表 Mapper
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 按模型聚合 Token 用量（仅统计助手消息）
     */
    @Select("SELECT model, COUNT(*) AS calls, COALESCE(SUM(token_count), 0) AS tokens " +
            "FROM message WHERE role = 'ASSISTANT' AND model IS NOT NULL " +
            "GROUP BY model ORDER BY tokens DESC")
    List<Map<String, Object>> usageByModel();
}
