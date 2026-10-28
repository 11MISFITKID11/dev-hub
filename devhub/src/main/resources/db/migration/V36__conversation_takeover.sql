-- 坐席实时接管：会话级人工接管状态机（NULL=AI / PENDING=待接管 / ACTIVE=坐席接管中）
ALTER TABLE conversation
    ADD COLUMN takeover_status VARCHAR(16) NULL COMMENT '人工接管状态：PENDING 待接入 / ACTIVE 接管中 / NULL=AI 模式',
    ADD COLUMN handler VARCHAR(32) NULL COMMENT '接管坐席用户名',
    ADD COLUMN takeover_ticket_id BIGINT NULL COMMENT '转人工联动的工单 ID';

-- 坐席实时会话菜单（挂"工单服务"目录 30 下，授权管理员/坐席角色 1）
INSERT INTO sys_menu (id, parent_id, name, path, perm, sort, menu_type) VALUES
  (31, 30, '实时会话', '/tickets/live', 'ticket:live', 45, 'C');
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 31;
