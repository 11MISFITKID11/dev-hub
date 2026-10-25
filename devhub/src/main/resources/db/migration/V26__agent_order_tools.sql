-- MVP-2 · Agentic 客服：mock 订单数据（与云帆科技知识库条款一致）+ 工具调用日志
CREATE TABLE mock_order (
  order_no VARCHAR(32) PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL COMMENT 'PAID/SHIPPED/IN_TRANSIT/DELIVERED/CANCELLED',
  carrier VARCHAR(32) NULL,
  tracking_no VARCHAR(32) NULL,
  items VARCHAR(200) NOT NULL,
  amount DECIMAL(10,2) NOT NULL,
  address VARCHAR(200) NULL,
  eta DATE NULL COMMENT '预计送达日',
  signed_at DATETIME NULL COMMENT '签收时间（7 天无理由起算点）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO mock_order (order_no, username, status, carrier, tracking_no, items, amount, address, eta, signed_at, create_time) VALUES
('CS20261023001', 'dev01', 'IN_TRANSIT', '顺丰速运', 'SF1388662200771', 'CloudSail G3 智能网关 ×2', 2748.00,
 '上海市浦东新区张江路 88 号 3 号仓', '2026-10-26', NULL, '2026-10-23 10:15:00'),
('CS20261015002', 'dev01', 'DELIVERED', '顺丰速运', 'SF1388221100993', 'S100 环境传感器 ×10', 1990.00,
 '上海市浦东新区张江路 88 号 3 号仓', NULL, '2026-10-18 14:30:00', '2026-10-15 09:00:00'),
('CS20261020003', 'admin', 'SHIPPED', '京东物流', 'JDVA0331998877', 'CloudSail G3 Pro ×1（含 POE 模块）', 2699.00,
 '杭州市滨江区云帆大厦 18 楼', '2026-10-26', NULL, '2026-10-20 16:20:00');

CREATE TABLE mock_logistics (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no VARCHAR(32) NOT NULL,
  event_time DATETIME NOT NULL,
  event VARCHAR(200) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO mock_logistics (order_no, event_time, event) VALUES
('CS20261023001', '2026-10-24 08:10:00', '包裹已从杭州滨江仓出库'),
('CS20261023001', '2026-10-24 18:40:00', '到达杭州萧山转运中心'),
('CS20261023001', '2026-10-25 07:20:00', '到达上海浦东集散中心，运输中'),
('CS20261015002', '2026-10-16 22:00:00', '包裹已从杭州滨江仓出库'),
('CS20261015002', '2026-10-18 09:15:00', '到达上海浦东张江营业点，派送中'),
('CS20261015002', '2026-10-18 14:30:00', '已签收，签收人：前台代收');

-- 工具调用审计（AI 自主解决率数据源）
CREATE TABLE agent_action (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  conversation_id VARCHAR(64) NULL,
  tool_name VARCHAR(64) NOT NULL,
  args VARCHAR(500) NULL,
  success TINYINT NOT NULL DEFAULT 1,
  elapsed_ms INT NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_aa_time (create_time),
  INDEX idx_aa_tool (tool_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
