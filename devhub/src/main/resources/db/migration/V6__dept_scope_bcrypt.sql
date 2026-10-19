-- 部门数据域：document 挂部门；NULL=全部门公共可见
ALTER TABLE document ADD COLUMN dept_id BIGINT NULL COMMENT '部门数据域（NULL=公共）';
UPDATE document SET dept_id = 1 WHERE deleted = 0;

-- 口令升级：{noop} 明文种子 → {bcrypt}（DelegatingPasswordEncoder 兼容读取）
UPDATE sys_user SET password = '{bcrypt}$2b$10$IZNxAI5j6H6lCoLQRDdZMutIs5I514RRylEvZk3Eytb.S8WaO7ex2' WHERE username = 'admin';
UPDATE sys_user SET password = '{bcrypt}$2b$10$pwQjYNbxF7YQTSVMUERB2Omho6pWq3wqd22s19.xThKb8pd1XYFOS' WHERE username = 'dev01';
