CREATE TABLE ai_app (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  app_key VARCHAR(32) NOT NULL COMMENT '公开凭据，挂件用',
  name VARCHAR(64) NOT NULL,
  description VARCHAR(255),
  system_prompt TEXT COMMENT '应用人设',
  opening_greeting VARCHAR(255) COMMENT '挂件开场白',
  dept_id BIGINT NULL COMMENT '检索数据域，NULL=全公司',
  rate_limit_per_min INT NOT NULL DEFAULT 20,
  allowed_origins VARCHAR(512) NULL COMMENT '挂件 Referer 白名单，逗号分隔，空=不限',
  enabled TINYINT NOT NULL DEFAULT 1,
  created_by VARCHAR(64),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_app_key (app_key),
  KEY idx_dept (dept_id)
);

CREATE TABLE api_key (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  app_id BIGINT NOT NULL,
  key_hash CHAR(64) NOT NULL COMMENT 'SHA-256，明文仅创建时返回一次',
  key_prefix VARCHAR(8) NOT NULL,
  name VARCHAR(64),
  enabled TINYINT NOT NULL DEFAULT 1,
  expires_at DATETIME NULL,
  last_used_at DATETIME NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_key_hash (key_hash),
  KEY idx_app (app_id)
);

-- 导航重排：知识中枢(原智能问答)置顶，研发协作收组
UPDATE sys_menu SET name='知识中枢', sort=1 WHERE id=6;
UPDATE sys_menu SET sort=2 WHERE id=5;
UPDATE sys_menu SET sort=3 WHERE id=1;
UPDATE sys_menu SET sort=5 WHERE id=10;
UPDATE sys_menu SET sort=6 WHERE id=11;
INSERT INTO sys_menu (id, parent_id, name, path, perm, sort, menu_type) VALUES
  (21, 6, 'AI 应用', '/ai/apps', 'ai:apps', 64, 'C'),
  (22, 0, '研发协作', NULL, NULL, 4, 'M');
UPDATE sys_menu SET parent_id=22, sort=1 WHERE id=2;
UPDATE sys_menu SET parent_id=22, sort=2 WHERE id=3;
UPDATE sys_menu SET parent_id=22, sort=3 WHERE id=4;
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, id FROM sys_menu WHERE id IN (21,22);
INSERT INTO sys_role_menu (role_id, menu_id)
  SELECT DISTINCT rm.role_id, 22 FROM sys_role_menu rm
   WHERE rm.menu_id IN (2,3,4) AND rm.role_id <> 1
     AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id=rm.role_id AND x.menu_id=22);
