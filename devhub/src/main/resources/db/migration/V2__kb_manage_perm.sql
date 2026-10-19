-- 知识库管理权限点（按钮级，挂"智能问答"目录下，不可见）
INSERT INTO sys_menu (parent_id, name, path, perm, sort, visible, menu_type) VALUES
  (6, '知识库管理', NULL, 'ai:kb:manage', 64, 0, 'C');
INSERT INTO sys_role_menu (role_id, menu_id)
  SELECT 1, MAX(id) FROM sys_menu WHERE perm = 'ai:kb:manage';
