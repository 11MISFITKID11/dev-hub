package com.devhub.module.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devhub.module.ticket.entity.TicketTag;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface TicketTagMapper extends BaseMapper<TicketTag> {

    /** 批量取工单标签（列表页展示用） */
    @Select("SELECT r.ticket_id AS ticketId, t.id AS tagId, t.name, t.color "
            + "FROM ticket_tag_rel r JOIN ticket_tag t ON t.id = r.tag_id AND t.deleted = 0 "
            + "WHERE r.ticket_id IN (${ids})")
    List<Map<String, Object>> tagsOfTickets(String ids);

    @org.apache.ibatis.annotations.Insert("INSERT IGNORE INTO ticket_tag_rel (ticket_id, tag_id) VALUES (#{ticketId}, #{tagId})")
    int attach(long ticketId, long tagId);

    @Delete("DELETE FROM ticket_tag_rel WHERE ticket_id = #{ticketId} AND tag_id = #{tagId}")
    int detach(long ticketId, long tagId);

    @Delete("DELETE FROM ticket_tag_rel WHERE tag_id = #{tagId}")
    int detachByTag(long tagId);
}
