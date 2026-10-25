-- 智能客服工单闭环（对标 AI 客服+人工工单双轨模式，第五环：结论回写知识库见知识运营）
-- AI 自动答不了的（CRAG 拒答/点踩）→ 转工单 → AI 分类定级 → 人工在 AI 建议下处理 → AI 质检 → 满意度
CREATE TABLE support_ticket (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ticket_no VARCHAR(32) NULL COMMENT '工单号 TK+6位序号',
  username VARCHAR(64) NOT NULL COMMENT '发起人',
  title VARCHAR(200) NOT NULL,
  content TEXT NOT NULL,
  source VARCHAR(20) NOT NULL DEFAULT 'manual' COMMENT '来源：manual/chat_refusal(拒答转单)/chat_dislike(点踩转单)',
  conversation_id VARCHAR(64) NULL COMMENT '来源会话（溯源）',
  message_id VARCHAR(64) NULL COMMENT '来源消息（溯源）',
  category VARCHAR(20) NULL COMMENT 'PRODUCT/BUG/ACCOUNT/BILLING/USAGE/OTHER（AI 识别，可人工改）',
  priority VARCHAR(10) NULL COMMENT 'HIGH/MEDIUM/LOW（AI 识别）',
  ai_classified TINYINT NOT NULL DEFAULT 0 COMMENT '当前分类是否来自 AI',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待受理/PROCESSING处理中/RESOLVED已解决/CLOSED已关闭',
  handler VARCHAR(64) NULL COMMENT '受理坐席',
  satisfaction TINYINT NULL COMMENT '发起人满意度 1-5',
  satisfaction_comment VARCHAR(500) NULL,
  qc_score INT NULL COMMENT 'AI 质检评分 0-100（低分<70进质量看板）',
  qc_result VARCHAR(1000) NULL COMMENT 'AI 质检明细 JSON',
  version INT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_status (status),
  KEY idx_user (username),
  KEY idx_cat (category),
  KEY idx_ctime (create_time)
);

CREATE TABLE support_ticket_reply (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ticket_id BIGINT NOT NULL,
  author VARCHAR(64) NOT NULL,
  role VARCHAR(10) NOT NULL COMMENT 'customer/agent',
  content TEXT NOT NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_ticket (ticket_id)
);

INSERT INTO sys_menu (id, parent_id, name, path, perm, sort, menu_type) VALUES
  (24, 6, '工单中心', '/tickets', 'ticket:manage', 66, 'C'),
  (25, 6, '我的工单', '/ticket/mine', 'ticket:mine', 67, 'C');
-- 坐席视角(工单中心)仅管理员；所有人都有"我的工单"（客户视角）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 24), (1, 25), (2, 25);
