-- P1 修复：补第二个坐席账号（转派/自动派单演示需要）
INSERT INTO sys_user (username, nickname, password, dept_id, deleted)
SELECT 'agent02', '坐席小王', password, dept_id, 0 FROM sys_user WHERE username = 'admin';
INSERT INTO sys_user_role (username, role_id)
SELECT 'agent02', id FROM sys_role WHERE role_key = 'ADMIN';
