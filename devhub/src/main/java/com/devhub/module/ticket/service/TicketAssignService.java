package com.devhub.module.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.devhub.common.NotificationService;
import com.devhub.module.ai.common.BusinessException;
import com.devhub.module.ticket.entity.SupportTicket;
import com.devhub.module.ticket.entity.SupportTicketReply;
import com.devhub.module.ticket.mapper.SupportTicketMapper;
import com.devhub.module.ticket.mapper.SupportTicketReplyMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动派单：AI 分类 → 路由规则（分类→技能组/部门）→ 组内坐席负载均衡（未结工单最少者优先）。
 * <p>派派单失败（无规则/无坐席）不阻断建单，工单留在"未分派"视图等人工认领。
 */
@Slf4j
@Service
public class TicketAssignService {

    private final SupportTicketMapper ticketMapper;
    private final SupportTicketReplyMapper replyMapper;
    private final JdbcTemplate jdbc;
    private final NotificationService notify;

    public TicketAssignService(SupportTicketMapper ticketMapper, SupportTicketReplyMapper replyMapper,
                               JdbcTemplate jdbc, NotificationService notify) {
        this.ticketMapper = ticketMapper;
        this.replyMapper = replyMapper;
        this.jdbc = jdbc;
        this.notify = notify;
    }

    /** 建单后自动分派：返回分派到的坐席（null=未分派） */
    public String autoAssign(SupportTicket t) {
        try {
            List<Long> deptIds = jdbc.queryForList(
                    "SELECT dept_id FROM ticket_route_rule WHERE category = ? AND enabled = 1",
                    Long.class, t.getCategory());
            if (deptIds.isEmpty() || deptIds.get(0) == null) return null;
            List<String> agents = agentsInDept(deptIds.get(0));
            if (agents.isEmpty()) return null;
            String picked = pickLeastLoaded(agents);
            ticketMapper.update(null, new LambdaUpdateWrapper<SupportTicket>()
                    .eq(SupportTicket::getId, t.getId())
                    .set(SupportTicket::getHandler, picked));
            t.setHandler(picked);
            notify.push(picked, "ticket", "新工单已分派给你：" + t.getTicketNo(),
                    t.getTitle(), "/#/tickets");
            return picked;
        } catch (Exception e) {
            log.warn("自动派单失败 ticket={}: {}", t.getTicketNo(), e.getMessage());
            return null;
        }
    }

    /** 候选坐席：目标部门有 ADMIN 角色（坐席）的启用用户；部门无人则回退全体坐席 */
    public List<String> agentsInDept(Long deptId) {
        String sql = "SELECT u.username FROM sys_user u "
                + "JOIN sys_user_role ur ON ur.username = u.username "
                + "JOIN sys_role r ON r.id = ur.role_id AND r.role_key = 'ADMIN' "
                + "WHERE u.deleted = 0";
        if (deptId != null) {
            List<String> in = jdbc.queryForList(sql + " AND u.dept_id = ?", String.class, deptId);
            if (!in.isEmpty()) return in;
        }
        return jdbc.queryForList(sql, String.class);
    }

    /** 负载均衡：未结（PENDING/PROCESSING）工单数最少者优先，并列取用户名稳定序 */
    public String pickLeastLoaded(List<String> agents) {
        Map<String, Long> load = loadMap(agents);
        return agents.stream()
                .min((a, b) -> {
                    int c = Long.compare(load.getOrDefault(a, 0L), load.getOrDefault(b, 0L));
                    return c != 0 ? c : a.compareTo(b);
                })
                .orElse(agents.get(0));
    }

    public Map<String, Long> loadMap(List<String> agents) {
        if (agents.isEmpty()) return Map.of();
        String in = String.join(",", agents.stream().map(a -> "'" + a.replace("'", "") + "'").toList());
        Map<String, Long> m = new LinkedHashMap<>();
        jdbc.query("SELECT handler, COUNT(*) AS c FROM support_ticket "
                        + "WHERE deleted = 0 AND status IN ('PENDING','PROCESSING') AND handler IN (" + in + ") "
                        + "GROUP BY handler",
                rs -> {
                    m.put(rs.getString("handler"), rs.getLong("c"));
                });
        agents.forEach(a -> m.putIfAbsent(a, 0L));
        return m;
    }

    /** 坐席候选列表（转派对话框用） */
    public List<Map<String, Object>> candidates() {
        return jdbc.queryForList("SELECT u.username, u.nickname, u.dept_id FROM sys_user u "
                + "JOIN sys_user_role ur ON ur.username = u.username "
                + "JOIN sys_role r ON r.id = ur.role_id AND r.role_key = 'ADMIN' "
                + "WHERE u.deleted = 0 ORDER BY u.username");
    }

    /** 转派：PENDING/PROCESSING 可转，落系统事件 + 通知新处理人 */
    public SupportTicket transfer(Long id, String to, String by) {
        SupportTicket t = ticketMapper.selectById(id);
        if (t == null) throw new BusinessException(404, "工单不存在");
        if (!"PENDING".equals(t.getStatus()) && !"PROCESSING".equals(t.getStatus()))
            throw new BusinessException(409, "仅待受理/处理中的工单可转派");
        if (to == null || to.isBlank()) throw new BusinessException("转派对象不能为空");
        int rows = ticketMapper.update(null, new LambdaUpdateWrapper<SupportTicket>()
                .eq(SupportTicket::getId, id)
                .in(SupportTicket::getStatus, "PENDING", "PROCESSING")
                .set(SupportTicket::getHandler, to));
        if (rows == 0) throw new BusinessException(409, "工单状态已变化，请刷新");
        SupportTicketReply event = new SupportTicketReply();
        event.setTicketId(id);
        event.setAuthor("system");
        event.setRole("system");
        event.setContent("工单由 " + by + " 转派给 " + to);
        replyMapper.insert(event);
        notify.push(to, "ticket", "工单转派给你：" + t.getTicketNo(), t.getTitle(), "/#/tickets");
        return ticketMapper.selectById(id);
    }
}
