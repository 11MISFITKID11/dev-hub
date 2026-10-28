-- 工单中心修复：TicketTag 实体含 create_time/update_time 审计字段，但 V25 建表缺失，
-- 导致打开处理抽屉加载标签列表时报 Unknown column 'create_time'
ALTER TABLE ticket_tag
    ADD COLUMN create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    ADD COLUMN update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
