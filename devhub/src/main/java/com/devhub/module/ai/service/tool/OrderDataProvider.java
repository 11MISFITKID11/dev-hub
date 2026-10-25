package com.devhub.module.ai.service.tool;

import java.util.List;
import java.util.Map;

/**
 * 订单数据源抽象（W4-7：演示 mock 与生产 API 实现的切换位）
 * <p>默认实现 MockOrderDataProvider 读 mock_order/mock_logistics（演示环境，与云帆知识库条款一致）；
 * 生产接入 ERP/订单中心时新增实现并 @Primary 覆盖，工具层零改动。
 */
public interface OrderDataProvider {

    /** 按订单号查询订单（不存在返回 null）；logistics 为该单轨迹（时间+事件），可为空 */
    OrderInfo queryByOrderNo(String orderNo);

    record OrderInfo(String orderNo, String username, String status, String carrier, String trackingNo,
                     String items, String amount, String address, String eta, String signedAt,
                     List<Map<String, Object>> logistics) {}
}
