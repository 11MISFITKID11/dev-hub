package com.devhub.module.ai.service.tool.builtin;

import com.devhub.module.ai.service.tool.BaseTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 订单与物流查询工具（Agentic 客服）
 * <p>查询 mock_order/mock_logistics（数据与云帆科技知识库条款一致）。
 * 数据隔离：仅可查本人订单（管理员例外），订单号是客户身份外的第二重校验。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderQueryTool extends BaseTool {

    private final JdbcTemplate jdbc;

    @Override
    public String getName() { return "query_order"; }

    @Override
    public String getDescription() {
        return "查询客户本人的订单状态与物流轨迹。当客户询问我的订单、物流到哪了、什么时候发货/送达、"
                + "是否签收等具体订单进展时调用。参数为订单号（形如 CS20261023001）。";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "orderNo": { "type": "string", "description": "订单号，形如 CS20261023001" }
                  },
                  "required": ["orderNo"]
                }
                """;
    }

    @Override
    protected void validate(Map<String, Object> parameters) {
        String orderNo = String.valueOf(parameters.getOrDefault("orderNo", "")).trim();
        if (orderNo.isBlank()) throw new IllegalArgumentException("orderNo 不能为空");
    }

    @Override
    protected String doExecute(Map<String, Object> parameters) {
        String orderNo = String.valueOf(parameters.get("orderNo")).trim().toUpperCase();
        Map<String, Object> order = jdbc.queryForMap(
                "SELECT order_no, username, status, carrier, tracking_no, items, amount, address, eta, signed_at "
                        + "FROM mock_order WHERE order_no = ?", orderNo);
        String me = SecurityContextHolder.getContext().getAuthentication().getName();
        boolean admin = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().contains("ADMIN"));
        if (!admin && !me.equals(order.get("username"))) {
            return "无权查看该订单：订单 " + orderNo + " 不属于当前账号，请核对订单号。";
        }
        List<Map<String, Object>> tracks = jdbc.queryForList(
                "SELECT event_time, event FROM mock_logistics WHERE order_no = ? ORDER BY event_time", orderNo);
        StringBuilder sb = new StringBuilder();
        sb.append("订单 ").append(order.get("order_no")).append("：")
                .append(statusCn(String.valueOf(order.get("status"))))
                .append("；商品：").append(order.get("items"))
                .append("；金额：").append(order.get("amount")).append(" 元")
                .append("；承运：").append(order.get("carrier")).append(" ")
                .append(order.get("tracking_no"));
        if (order.get("eta") != null) sb.append("；预计送达：").append(order.get("eta"));
        if (order.get("signed_at") != null) {
            sb.append("；签收时间：").append(order.get("signed_at"))
                    .append("（7 天无理由退货自签收之日起算 7 个自然日）");
        }
        if (!tracks.isEmpty()) {
            sb.append("\n物流轨迹：");
            for (Map<String, Object> t : tracks) {
                sb.append("\n- ").append(t.get("event_time")).append(" ").append(t.get("event"));
            }
        }
        return sb.toString();
    }

    private String statusCn(String s) {
        return switch (s) {
            case "PAID" -> "已付款/备货中";
            case "SHIPPED", "IN_TRANSIT" -> "运输中";
            case "DELIVERED" -> "已签收";
            case "CANCELLED" -> "已取消";
            default -> s;
        };
    }
}
