package com.devhub.module.ai.service.tool.builtin;

import com.devhub.module.ai.service.tool.BaseTool;
import com.devhub.module.ai.service.tool.OrderDataProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 订单与物流查询工具（Agentic 客服）
 * <p>数据源走 {@link OrderDataProvider} 抽象（默认 mock 演示库，生产可换 ERP 实现零改工具层）。
 * 数据隔离：仅可查本人订单（管理员例外），订单号是客户身份外的第二重校验。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderQueryTool extends BaseTool {

    private final OrderDataProvider orders;

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
        OrderDataProvider.OrderInfo o = orders.queryByOrderNo(orderNo);
        if (o == null) return "未找到订单：" + orderNo + "，请核对订单号。";
        String me = SecurityContextHolder.getContext().getAuthentication().getName();
        boolean admin = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().contains("ADMIN"));
        if (!admin && !me.equals(o.username())) {
            return "无权查看该订单：订单 " + orderNo + " 不属于当前账号，请核对订单号。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("订单 ").append(o.orderNo()).append("：").append(statusCn(o.status()))
                .append("；商品：").append(o.items())
                .append("；金额：").append(o.amount()).append(" 元")
                .append("；承运：").append(o.carrier()).append(" ").append(o.trackingNo());
        if (o.eta() != null) sb.append("；预计送达：").append(o.eta());
        if (o.signedAt() != null) {
            sb.append("；签收时间：").append(o.signedAt())
                    .append("（7 天无理由退货自签收之日起算 7 个自然日）");
        }
        List<Map<String, Object>> tracks = o.logistics();
        if (tracks != null && !tracks.isEmpty()) {
            sb.append("\n物流轨迹：");
            for (Map<String, Object> t : tracks) {
                sb.append("\n- ").append(t.get("event_time")).append(" ").append(t.get("event"));
            }
        }
        return sb.toString();
    }

    private String statusCn(String s) {
        return switch (s == null ? "" : s) {
            case "PAID" -> "已付款/备货中";
            case "SHIPPED", "IN_TRANSIT" -> "运输中";
            case "DELIVERED" -> "已签收";
            case "CANCELLED" -> "已取消";
            default -> s;
        };
    }
}
