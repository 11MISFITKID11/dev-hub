-- =====================================================================
-- V9：P0 深化——协作（评论/动态）+ 迭代度量（燃尽快照）+ 任务时间戳
-- =====================================================================

CREATE TABLE prj_comment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  obj_type VARCHAR(16) NOT NULL COMMENT 'TASK/DEFECT',
  obj_id BIGINT NOT NULL,
  content VARCHAR(2000) NOT NULL,
  creator VARCHAR(64) NOT NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_obj (obj_type, obj_id)
) COMMENT '对象评论（Linear 式协作流）';

CREATE TABLE prj_activity (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  obj_type VARCHAR(16) NOT NULL COMMENT 'TASK/DEFECT/ITERATION',
  obj_id BIGINT NOT NULL,
  action VARCHAR(32) NOT NULL COMMENT 'create/assign/status/comment/field',
  field_name VARCHAR(32) DEFAULT NULL,
  old_value VARCHAR(255) DEFAULT NULL,
  new_value VARCHAR(255) DEFAULT NULL,
  actor VARCHAR(64) NOT NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_obj (obj_type, obj_id)
) COMMENT '对象操作动态时间线（含字段 diff，效能报表取 status 变更时间戳）';

-- 任务进入 DONE 的时间：cycle time 计算源（无则从 activity 兜底）
ALTER TABLE prj_task ADD COLUMN done_at DATETIME NULL COMMENT '完成时间戳（cycle time 用）';
ALTER TABLE prj_task ADD COLUMN start_at DATETIME NULL COMMENT '首次进入 DOING 时间戳';

CREATE TABLE prj_burndown (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  iteration_id BIGINT NOT NULL,
  snapshot_date DATE NOT NULL,
  total_tasks INT DEFAULT 0,
  open_tasks INT DEFAULT 0,
  done_tasks INT DEFAULT 0,
  UNIQUE KEY uk_it_date (iteration_id, snapshot_date)
) COMMENT '迭代燃尽图按日快照';
