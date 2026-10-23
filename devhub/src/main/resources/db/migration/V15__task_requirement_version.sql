-- 乐观锁版本号：任务/需求并发编辑冲突检测（UPDATE ... WHERE version = ?，冲突方更新 0 行）
ALTER TABLE prj_task ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE prj_requirement ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
