-- 演示数据支撑：补 3 个客户账号（工单来自不同企业客户，可登录体验"我的工单"）
INSERT INTO sys_user (username, nickname, password, dept_id, deleted)
SELECT 'cust03', '王经理（联华超市）', password, 2, 0 FROM sys_user WHERE username = 'dev01';
INSERT INTO sys_user (username, nickname, password, dept_id, deleted)
SELECT 'cust04', '李仓管（中远仓储）', password, 2, 0 FROM sys_user WHERE username = 'dev01';
INSERT INTO sys_user (username, nickname, password, dept_id, deleted)
SELECT 'cust05', '赵店长（鲜丰水果）', password, 2, 0 FROM sys_user WHERE username = 'dev01';
INSERT INTO sys_user_role (username, role_id)
SELECT u.username, r.id FROM sys_user u, sys_role r
WHERE u.username IN ('cust03','cust04','cust05') AND r.role_key = 'USER';
