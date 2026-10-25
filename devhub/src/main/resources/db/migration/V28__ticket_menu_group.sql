-- 导航重组：智能客服下工单相关菜单（工单中心/我的工单/质检统计）独立成「工单服务」一级分组
-- 顶部一级排序：智能客服(1) 工单服务(2) 知识库(3) 工作台(4) 运营报表(5) 系统管理(6)
UPDATE sys_menu SET sort = sort + 1 WHERE parent_id = 0 AND sort >= 2;

INSERT INTO sys_menu (id, parent_id, name, path, perm, sort, visible, menu_type) VALUES
  (30, 0, '工单服务', NULL, NULL, 2, 1, 'M');

UPDATE sys_menu SET parent_id = 30, sort = 1 WHERE id = 24;  -- 工单中心
UPDATE sys_menu SET parent_id = 30, sort = 2 WHERE id = 25;  -- 我的工单
UPDATE sys_menu SET parent_id = 30, sort = 3 WHERE id = 27;  -- 质检统计

-- 新分组授权：ADMIN 全量；USER（客户）保有"我的工单"入口（挂在分组下需同时持有父节点）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 30), (2, 30);
