-- W2-4: 细粒度权限点（角色-权限，接口级 @RequirePerm 校验）
CREATE TABLE sys_role_perm (
  role_id BIGINT NOT NULL,
  perm VARCHAR(64) NOT NULL,
  PRIMARY KEY (role_id, perm)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- ADMIN 角色授予全部内测权限点（坐席级角色未来按需裁剪）
INSERT INTO sys_role_perm (role_id, perm)
SELECT r.id, p.perm FROM sys_role r,
 (SELECT 'ticket:transfer' perm UNION SELECT 'ticket:qc' UNION SELECT 'ticket:close') p
WHERE r.role_key = 'ADMIN';
