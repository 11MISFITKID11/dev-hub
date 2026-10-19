-- =====================================================================
-- V12：P1-5 自定义工作流引擎（元数据驱动：项目级状态集 + 流转规则 + WIP 限制）
-- =====================================================================

CREATE TABLE prj_workflow_state (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id BIGINT NOT NULL,
  name VARCHAR(64) NOT NULL,
  category VARCHAR(16) NOT NULL COMMENT 'TODO/DOING/DONE（看板列分组与统计类别）',
  sort INT DEFAULT 0,
  is_default_start TINYINT DEFAULT 0 COMMENT '新任务初始状态',
  wip_limit INT NULL COMMENT '该状态同时持有的任务上限（NULL=不限）',
  deleted TINYINT DEFAULT 0,
  UNIQUE KEY uk_proj_name (project_id, name)
) COMMENT '项目工作流状态定义';

CREATE TABLE prj_workflow_transition (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id BIGINT NOT NULL,
  from_state VARCHAR(64) NOT NULL,
  to_state VARCHAR(64) NOT NULL,
  UNIQUE KEY uk_trans (project_id, from_state, to_state)
) COMMENT '允许的流转规则';

-- 默认工作流模板（存量项目 + 新建项目初始化用）
INSERT INTO prj_workflow_state (project_id, name, category, sort, is_default_start, wip_limit) VALUES
  (0, 'TODO', 'TODO', 1, 1, NULL),
  (0, 'DOING', 'DOING', 2, 0, 5),
  (0, 'DONE', 'DONE', 3, 0, NULL);
INSERT INTO prj_workflow_transition (project_id, from_state, to_state) VALUES
  (0, 'TODO', 'DOING'), (0, 'DOING', 'DONE'), (0, 'DOING', 'TODO'), (0, 'DONE', 'DOING');
-- 存量项目套用默认模板
INSERT INTO prj_workflow_state (project_id, name, category, sort, is_default_start, wip_limit)
  SELECT p.id, s.name, s.category, s.sort, s.is_default_start, s.wip_limit
  FROM prj_project p CROSS JOIN prj_workflow_state s WHERE s.project_id = 0 AND p.deleted = 0;
INSERT INTO prj_workflow_transition (project_id, from_state, to_state)
  SELECT p.id, t.from_state, t.to_state
  FROM prj_project p CROSS JOIN prj_workflow_transition t WHERE t.project_id = 0 AND p.deleted = 0;
