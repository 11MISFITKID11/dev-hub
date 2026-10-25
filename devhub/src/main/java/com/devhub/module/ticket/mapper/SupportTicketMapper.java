package com.devhub.module.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devhub.module.ticket.entity.SupportTicket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface SupportTicketMapper extends BaseMapper<SupportTicket> {

    @Select("SELECT status AS k, COUNT(*) AS v FROM support_ticket WHERE deleted = 0 GROUP BY status")
    List<Map<String, Object>> countByStatus();

    @Select("SELECT category AS k, COUNT(*) AS v FROM support_ticket WHERE deleted = 0 AND category IS NOT NULL GROUP BY category")
    List<Map<String, Object>> countByCategory();

    @Select("SELECT priority AS k, COUNT(*) AS v FROM support_ticket WHERE deleted = 0 AND priority IS NOT NULL GROUP BY priority")
    List<Map<String, Object>> countByPriority();

    @Select("SELECT IFNULL(AVG(satisfaction), 0) AS sat, IFNULL(AVG(qc_score), 0) AS qc, COUNT(*) AS total "
            + "FROM support_ticket WHERE deleted = 0")
    Map<String, Object> overall();

    @Select("SELECT * FROM support_ticket WHERE deleted = 0 AND qc_score IS NOT NULL AND qc_score < 70 "
            + "ORDER BY qc_score ASC LIMIT 5")
    List<SupportTicket> lowQcTop5();

    @Select("SELECT DATE_FORMAT(create_time, '%m-%d') AS d, COUNT(*) AS created FROM support_ticket "
            + "WHERE deleted = 0 AND create_time >= DATE_SUB(NOW(), INTERVAL 7 DAY) GROUP BY DATE_FORMAT(create_time, '%m-%d')")
    List<Map<String, Object>> trend7d();
}
