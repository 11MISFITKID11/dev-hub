package com.devhub.module.ai.mapper;

import com.devhub.module.ai.model.entity.Message;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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

    /**
     * 管理员侧问答评价分页：助手消息 + 触发问题（就近前一条用户消息）+ 会话/用户信息。
     * 过滤参数为 null 时该条件不生效（SQL 三值逻辑：TRUE OR NULL = TRUE）。
     */
    @Select("SELECT m.id, m.conversation_id AS conversationId, c.title, c.user_id AS username, " +
            "q.content AS question, m.content AS answer, m.feedback, m.faith_score AS faithScore, " +
            "m.model, m.create_time AS createTime " +
            "FROM message m " +
            "JOIN conversation c ON c.id = m.conversation_id AND c.deleted = 0 " +
            "LEFT JOIN message q ON q.conversation_id = m.conversation_id AND q.role = 'USER' " +
            "  AND q.create_time = (SELECT MAX(q2.create_time) FROM message q2 " +
            "      WHERE q2.conversation_id = m.conversation_id AND q2.role = 'USER' " +
            "        AND q2.create_time <= m.create_time AND q2.deleted = 0) " +
            "WHERE m.deleted = 0 AND m.role = 'ASSISTANT' " +
            "  AND (#{feedback} IS NULL OR (#{feedback} = 0 AND m.feedback IS NULL) OR m.feedback = #{feedback}) " +
            "  AND (#{username} IS NULL OR c.user_id LIKE CONCAT('%', #{username}, '%')) " +
            "  AND (#{start} IS NULL OR m.create_time >= #{start}) " +
            "  AND (#{end} IS NULL OR m.create_time < #{end}) " +
            "ORDER BY m.create_time DESC")
    IPage<Map<String, Object>> feedbackPage(IPage<?> page,
                                            @Param("feedback") Integer feedback,
                                            @Param("username") String username,
                                            @Param("start") String start,
                                            @Param("end") String end);
}
