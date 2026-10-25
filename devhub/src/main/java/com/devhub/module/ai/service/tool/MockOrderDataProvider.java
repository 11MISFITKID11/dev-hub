package com.devhub.module.ai.service.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 演示数据源：mock_order / mock_logistics（数据与云帆知识库条款一致） */
@Component
@RequiredArgsConstructor
public class MockOrderDataProvider implements OrderDataProvider {

    private final JdbcTemplate jdbc;

    @Override
    public OrderInfo queryByOrderNo(String orderNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT order_no, username, status, carrier, tracking_no, items, amount, address, eta, signed_at "
                        + "FROM mock_order WHERE order_no = ?", orderNo.toUpperCase());
        if (rows.isEmpty()) return null;
        Map<String, Object> o = rows.get(0);
        List<Map<String, Object>> logistics = jdbc.queryForList(
                "SELECT event_time, event FROM mock_logistics WHERE order_no = ? ORDER BY event_time",
                orderNo.toUpperCase());
        return new OrderInfo(
                String.valueOf(o.get("order_no")), String.valueOf(o.get("username")),
                String.valueOf(o.get("status")), String.valueOf(o.get("carrier")),
                String.valueOf(o.get("tracking_no")), String.valueOf(o.get("items")),
                String.valueOf(o.get("amount")), String.valueOf(o.get("address")),
                o.get("eta") == null ? null : String.valueOf(o.get("eta")),
                o.get("signed_at") == null ? null : String.valueOf(o.get("signed_at")),
                logistics);
    }
}
