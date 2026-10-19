-- V13 需求层 + 子任务 + 任务依赖 + 附件（P0 补齐，对标 Jira/Linear 骨架）

-- ---------- 需求：Epic / Story 两级 ----------
CREATE TABLE prj_requirement (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id     BIGINT NOT NULL,
  parent_id      BIGINT DEFAULT NULL COMMENT 'Story 挂 Epic（NULL=顶层 Epic）',
  rtype          VARCHAR(16) NOT NULL DEFAULT 'STORY' COMMENT 'EPIC/STORY',
  title          VARCHAR(200) NOT NULL,
  description    TEXT,
  status         VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/DEVELOPING/DONE/REJECTED',
  priority       VARCHAR(8)  DEFAULT 'M',
  story_points   INT         DEFAULT NULL,
  owner_username VARCHAR(64),
  iteration_id   BIGINT      DEFAULT NULL,
  deleted        INT DEFAULT 0,
  create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_proj (project_id, rtype)
) COMMENT '需求池（Epic 分组 + Story）';

-- 任务可挂到 Story
ALTER TABLE prj_task ADD COLUMN requirement_id BIGINT DEFAULT NULL COMMENT '关联需求(Story)';

-- ---------- 子任务清单（Trello/Linear 式 checklist） ----------
CREATE TABLE prj_subtask (
  id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  title   VARCHAR(200) NOT NULL,
  done    INT DEFAULT 0,
  sort    INT DEFAULT 0,
  deleted INT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_task (task_id)
) COMMENT '任务子清单';

-- ---------- 任务依赖（blocked-by / blocking） ----------
CREATE TABLE prj_task_dep (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id    BIGINT NOT NULL COMMENT '被阻塞的任务',
  depends_on BIGINT NOT NULL COMMENT '前置任务（完成后方可结项）',
  deleted    INT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_dep (task_id, depends_on)
) COMMENT '任务依赖关系';

-- ---------- 对象附件 ----------
CREATE TABLE prj_attachment (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  obj_type     VARCHAR(16) NOT NULL COMMENT 'TASK/DEFECT',
  obj_id       BIGINT NOT NULL,
  file_name    VARCHAR(255) NOT NULL,
  file_size    BIGINT,
  content_type VARCHAR(128),
  stored_path  VARCHAR(512) NOT NULL,
  uploader     VARCHAR(64),
  deleted      INT DEFAULT 0,
  create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_obj (obj_type, obj_id)
) COMMENT '任务/缺陷附件';

-- ---------- 菜单：需求管理（插在项目管理之后） ----------
UPDATE sys_menu SET sort = sort + 1 WHERE parent_id = 0 AND sort >= 3;
INSERT INTO sys_menu (parent_id, name, path, perm, sort, menu_type)
  VALUES (0, '需求管理', '/requirement', NULL, 3, 'M');
INSERT INTO sys_role_menu (role_id, menu_id)
  SELECT r.id, m.id FROM sys_menu m CROSS JOIN sys_role r
  WHERE m.path = '/requirement' AND r.role_key IN ('ADMIN', 'USER');
