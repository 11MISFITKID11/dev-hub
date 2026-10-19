-- =====================================================================
-- DevHub V1 基线：宿主骨架（sys_*）+ AI 模块表（自 agent-platform 平移，独立 devhub 库）
-- =====================================================================

-- ---------- 宿主骨架：RBAC ----------
CREATE TABLE sys_user (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  username  VARCHAR(64)  NOT NULL UNIQUE,
  password  VARCHAR(128) NOT NULL COMMENT '{noop}骨架明文；正式版换{bcrypt}',
  nickname  VARCHAR(64),
  dept_id   BIGINT DEFAULT NULL,
  status    CHAR(1) DEFAULT '0' COMMENT '0正常 1停用',
  deleted   TINYINT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '用户';

CREATE TABLE sys_role (
  id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_key VARCHAR(64)  NOT NULL UNIQUE COMMENT 'ADMIN/USER',
  name     VARCHAR(64)  NOT NULL,
  deleted  TINYINT DEFAULT 0
) COMMENT '角色';

CREATE TABLE sys_menu (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  parent_id BIGINT DEFAULT 0,
  name      VARCHAR(64) NOT NULL,
  path      VARCHAR(128),
  perm      VARCHAR(128) COMMENT '权限点，如 ai:ops',
  sort      INT DEFAULT 0,
  visible   TINYINT DEFAULT 1,
  menu_type CHAR(1) DEFAULT 'M' COMMENT 'M目录 C页面'
) COMMENT '菜单（骨架阶段只存结构，供登录后渲染侧栏）';

CREATE TABLE sys_user_role (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  role_id  BIGINT NOT NULL,
  UNIQUE KEY uk_user_role (username, role_id)
) COMMENT '用户-角色';

CREATE TABLE sys_role_menu (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_id BIGINT NOT NULL,
  menu_id BIGINT NOT NULL,
  UNIQUE KEY uk_role_menu (role_id, menu_id)
) COMMENT '角色-菜单';

-- ---------- 种子：用户/角色/菜单 ----------
INSERT INTO sys_user (username, password, nickname) VALUES
  ('admin', '{noop}admin123', '系统管理员'),
  ('dev01',  '{noop}dev123',  '开发小张');

INSERT INTO sys_role (role_key, name) VALUES ('ADMIN', '管理员'), ('USER', '普通用户');

-- 菜单树（方案 2.1 的 8 个一级 + 智能问答子项）
INSERT INTO sys_menu (id, parent_id, name, path, perm, sort, menu_type) VALUES
  (1, 0, '工作台',   '/dashboard', NULL, 1, 'M'),
  (2, 0, '项目管理', '/project',   NULL, 2, 'M'),
  (3, 0, '任务中心', '/task',      NULL, 3, 'M'),
  (4, 0, '缺陷跟踪', '/defect',    NULL, 4, 'M'),
  (5, 0, '文档中心', '/doc',       NULL, 5, 'M'),
  (6, 0, '智能问答', '/ai',        NULL, 6, 'M'),
  (7, 6, '对话问答', '/ai/chat',    'ai:chat',    61, 'C'),
  (8, 6, '会话历史', '/ai/history', 'ai:history', 62, 'C'),
  (9, 6, '知识运营', '/ai/ops',     'ai:ops',     63, 'C'),
  (10, 0, '报表统计', '/report',    NULL, 7, 'M'),
  (11, 0, '系统管理', '/system',    NULL, 8, 'M');

INSERT INTO sys_user_role (username, role_id) VALUES ('admin', 1), ('dev01', 2);

-- ADMIN：全部菜单；USER：工作台/文档中心/智能问答（含对话+历史，不含知识运营）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu;
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
  (2,1),(2,5),(2,6),(2,7),(2,8);

-- =====================================================================
-- AI 模块表（沿用 agent-platform 结构，新增用户归属列；表名保持原样，
-- 独立 devhub 库内无冲突——方案 5.4 的 kb_*/ai_* 前缀重命名留到正式版）
-- =====================================================================

CREATE TABLE conversation (
  id VARCHAR(64) PRIMARY KEY,
  title VARCHAR(255),
  user_id VARCHAR(64) DEFAULT NULL COMMENT '归属用户（登录名）',
  turn_count INT DEFAULT 0,
  deleted TINYINT DEFAULT 0,
  create_time DATETIME DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  KEY idx_user (user_id),
  KEY idx_update_time (update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI会话';

CREATE TABLE message (
  id VARCHAR(64) PRIMARY KEY,
  conversation_id VARCHAR(64),
  role VARCHAR(20),
  content TEXT,
  token_count INT,
  model VARCHAR(64),
  faith_score DOUBLE DEFAULT NULL,
  deleted TINYINT DEFAULT 0,
  create_time DATETIME DEFAULT NULL,
  KEY idx_conv (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI消息';

CREATE TABLE document (
  id VARCHAR(64) PRIMARY KEY,
  file_name VARCHAR(512),
  file_type VARCHAR(32),
  file_size BIGINT,
  chunk_count INT,
  status VARCHAR(20) DEFAULT 'completed',
  life_status VARCHAR(20) DEFAULT 'active',
  source_type VARCHAR(20) DEFAULT 'upload',
  expire_at DATETIME DEFAULT NULL,
  version INT DEFAULT 1,
  superseded_by VARCHAR(64) DEFAULT NULL,
  hit_count INT DEFAULT 0,
  source_conversation_id VARCHAR(64) DEFAULT NULL,
  urls TEXT,
  deleted TINYINT DEFAULT 0,
  create_time DATETIME DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档';

CREATE TABLE document_chunk (
  id VARCHAR(64) PRIMARY KEY,
  document_id VARCHAR(64),
  file_name VARCHAR(512),
  content TEXT,
  chunk_index INT,
  parent_id VARCHAR(64) DEFAULT NULL,
  chunk_type TINYINT DEFAULT NULL,
  doc_status VARCHAR(20) DEFAULT 'active',
  deleted TINYINT DEFAULT 0,
  create_time DATETIME DEFAULT NULL,
  KEY idx_doc (document_id),
  KEY idx_chunk_parent (parent_id),
  FULLTEXT KEY ft_content (content) /*!50100 WITH PARSER `ngram` */
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识切片';

CREATE TABLE knowledge_gap (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  question_hash VARCHAR(64) NOT NULL,
  question TEXT NOT NULL,
  conversation_id VARCHAR(64),
  status VARCHAR(20) DEFAULT 'open',
  hit_count INT DEFAULT 1,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_hash (question_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识缺口';

CREATE TABLE knowledge_feedback (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  document_id VARCHAR(64) NOT NULL,
  useful TINYINT NOT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_doc (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识反馈';

CREATE TABLE ai_usage (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  stat_date DATE NOT NULL,
  username VARCHAR(64) NOT NULL,
  model VARCHAR(64),
  prompt_tokens BIGINT DEFAULT 0,
  completion_tokens BIGINT DEFAULT 0,
  KEY idx_date_user (stat_date, username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI用量（骨架版：按日/用户/模型）';
