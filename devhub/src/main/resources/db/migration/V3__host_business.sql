-- =====================================================================
-- V3：宿主业务（部门/字典/日志/项目/迭代/任务/缺陷/文档分类 + 权限补授）
-- 设计借鉴 RuoYi：部门树、字典、@Log 操作日志表
-- =====================================================================

CREATE TABLE sys_dept (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  parent_id BIGINT DEFAULT 0,
  name VARCHAR(64) NOT NULL,
  sort INT DEFAULT 0,
  deleted TINYINT DEFAULT 0
) COMMENT '部门树';

CREATE TABLE sys_dict_data (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  type VARCHAR(64) NOT NULL,
  label VARCHAR(64) NOT NULL,
  value VARCHAR(64) NOT NULL,
  sort INT DEFAULT 0
) COMMENT '字典数据';

CREATE TABLE sys_oper_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  module VARCHAR(64), action VARCHAR(64), method VARCHAR(128),
  params VARCHAR(2000), username VARCHAR(64), ip VARCHAR(64),
  status TINYINT DEFAULT 1, error_msg VARCHAR(500), cost_ms BIGINT,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_time (create_time)
) COMMENT '操作日志（@OperLog AOP 写入）';

CREATE TABLE sys_login_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64), ip VARCHAR(64), status TINYINT, msg VARCHAR(128),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) COMMENT '登录日志';

CREATE TABLE prj_project (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(128) NOT NULL, code VARCHAR(64),
  owner_username VARCHAR(64), dept_id BIGINT,
  status TINYINT DEFAULT 0 COMMENT '0进行中 1归档',
  start_date DATE, end_date DATE, description VARCHAR(500),
  deleted TINYINT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '项目';

CREATE TABLE prj_iteration (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id BIGINT NOT NULL, name VARCHAR(128) NOT NULL,
  start_date DATE, end_date DATE, status TINYINT DEFAULT 0,
  deleted TINYINT DEFAULT 0
) COMMENT '迭代';

CREATE TABLE prj_member (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id BIGINT NOT NULL, username VARCHAR(64) NOT NULL, role_key VARCHAR(32) DEFAULT 'DEV',
  UNIQUE KEY uk_pm (project_id, username)
) COMMENT '项目成员';

CREATE TABLE prj_task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id BIGINT NOT NULL, iteration_id BIGINT,
  title VARCHAR(200) NOT NULL, content VARCHAR(2000),
  status VARCHAR(16) DEFAULT 'TODO' COMMENT 'TODO/DOING/DONE',
  priority VARCHAR(8) DEFAULT 'M' COMMENT 'L/M/H/U',
  assignee_username VARCHAR(64), creator_username VARCHAR(64), due_date DATE,
  deleted TINYINT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_project (project_id), KEY idx_assignee (assignee_username)
) COMMENT '任务';

CREATE TABLE prj_defect (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id BIGINT NOT NULL, task_id BIGINT,
  title VARCHAR(200) NOT NULL, description VARCHAR(2000),
  severity VARCHAR(16) DEFAULT 'NORMAL' COMMENT 'FATAL/SERIOUS/NORMAL/MINOR',
  status VARCHAR(16) DEFAULT 'OPEN' COMMENT 'OPEN/FIXED/CLOSED',
  reporter_username VARCHAR(64), assignee_username VARCHAR(64),
  deleted TINYINT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_project (project_id)
) COMMENT '缺陷';

CREATE TABLE doc_category (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  parent_id BIGINT DEFAULT 0, name VARCHAR(64) NOT NULL, sort INT DEFAULT 0,
  deleted TINYINT DEFAULT 0
) COMMENT '文档分类';

-- 文档中心治理字段（挂在既有 document 表上，AI 检索链路不动）
ALTER TABLE document
  ADD COLUMN category_id BIGINT NULL,
  ADD COLUMN uploader VARCHAR(64) NULL,
  ADD COLUMN audit_status VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/PUBLISHED/REJECTED';

-- 现有已入库文档默认已发布
UPDATE document SET audit_status='PUBLISHED', uploader='admin' WHERE deleted=0;

-- ===== 种子 =====
INSERT INTO sys_dept (id, parent_id, name, sort) VALUES (1,0,'DevHub科技',0),(2,1,'研发部',1),(3,1,'测试部',2);
UPDATE sys_user SET dept_id=1 WHERE username='admin'; UPDATE sys_user SET dept_id=2 WHERE username='dev01';

INSERT INTO sys_dict_data (type,label,value,sort) VALUES
 ('task_status','待办','TODO',1),('task_status','进行中','DOING',2),('task_status','已完成','DONE',3),
 ('task_priority','低','L',1),('task_priority','中','M',2),('task_priority','高','H',3),('task_priority','紧急','U',4),
 ('defect_severity','致命','FATAL',1),('defect_severity','严重','SERIOUS',2),('defect_severity','一般','NORMAL',3),('defect_severity','轻微','MINOR',4),
 ('defect_status','待修复','OPEN',1),('defect_status','已修复','FIXED',2),('defect_status','已关闭','CLOSED',3),
 ('project_status','进行中','0',1),('project_status','已归档','1',2),
 ('member_role','负责人','OWNER',1),('member_role','开发','DEV',2),('member_role','测试','QA',3);

INSERT INTO doc_category (id, parent_id, name, sort) VALUES (1,0,'研发规范',1),(2,0,'工具手册',2),(3,0,'架构设计',3);
UPDATE document SET category_id=1 WHERE file_name LIKE '%手册%';
UPDATE document SET category_id=2 WHERE file_name LIKE '%Arthas%';

-- 权限补授：系统管理/报表挂权限点；USER 角色补业务菜单
UPDATE sys_menu SET perm='system:manage' WHERE id=11;
UPDATE sys_menu SET perm='report:view' WHERE id=10;
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (2,2),(2,3),(2,4),(2,10);
