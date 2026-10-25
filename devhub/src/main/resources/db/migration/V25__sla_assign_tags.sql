-- MVP-1 · 工单运营补课：SLA 引擎 + 自动派单路由 + 标签
-- 1) SLA 策略：按优先级定义首响/解决双时钟（分钟）
CREATE TABLE sla_policy (
  priority VARCHAR(10) PRIMARY KEY,
  first_response_minutes INT NOT NULL,
  resolve_minutes INT NOT NULL,
  description VARCHAR(200)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO sla_policy (priority, first_response_minutes, resolve_minutes, description) VALUES
  ('HIGH',   30,  240, '高：30 分钟首响 / 4 小时解决'),
  ('MEDIUM', 120, 1440, '中：2 小时首响 / 24 小时解决'),
  ('LOW',    240, 2880, '低：4 小时首响 / 48 小时解决');

-- 2) 工单 SLA 时钟字段（双时钟 + 等待客户暂停累计）
ALTER TABLE support_ticket
  ADD COLUMN sla_first_due DATETIME NULL COMMENT '首响截止',
  ADD COLUMN sla_resolve_due DATETIME NULL COMMENT '解决截止',
  ADD COLUMN sla_first_at DATETIME NULL COMMENT '实际首响时间',
  ADD COLUMN sla_breach TINYINT NOT NULL DEFAULT 0 COMMENT '是否已 SLA 超时',
  ADD COLUMN sla_pause_ms BIGINT NOT NULL DEFAULT 0 COMMENT '等待客户累计暂停毫秒',
  ADD COLUMN sla_paused_at DATETIME NULL COMMENT '当前暂停起点（NULL=计时中）',
  ADD INDEX idx_sla (sla_breach, sla_resolve_due);

-- 存量进行中工单按当前优先级回填时钟
UPDATE support_ticket t
JOIN sla_policy p ON p.priority = t.priority
SET t.sla_first_due = DATE_ADD(t.create_time, INTERVAL p.first_response_minutes MINUTE),
    t.sla_resolve_due = DATE_ADD(t.create_time, INTERVAL p.resolve_minutes MINUTE)
WHERE t.deleted = 0 AND t.status IN ('PENDING', 'PROCESSING') AND t.sla_resolve_due IS NULL;

-- 3) 自动派单路由：AI 分类 → 技能组（部门）
CREATE TABLE ticket_route_rule (
  category VARCHAR(20) PRIMARY KEY,
  dept_id BIGINT NULL COMMENT '目标部门，NULL=不分派',
  enabled TINYINT NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO ticket_route_rule (category, dept_id)
SELECT d.value, 1 FROM sys_dict_data d WHERE d.type = 'ticket_category';

-- 4) 工单标签
CREATE TABLE ticket_tag (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(32) NOT NULL UNIQUE,
  color VARCHAR(16) NOT NULL DEFAULT '#0071e3',
  deleted TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ticket_tag_rel (
  ticket_id BIGINT NOT NULL,
  tag_id BIGINT NOT NULL,
  PRIMARY KEY (ticket_id, tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO ticket_tag (name, color) VALUES
  ('VIP客户', '#7c5cff'), ('退款', '#e6633a'), ('上门服务', '#0e9f6e'), ('重复投诉', '#d97706');
