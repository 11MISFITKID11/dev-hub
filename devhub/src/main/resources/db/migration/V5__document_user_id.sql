-- 迁移的 Document 实体含 user_id（上传人）列，devhub V1 建表遗漏
ALTER TABLE document ADD COLUMN user_id VARCHAR(64) NULL COMMENT '上传人（agent-platform 原字段）';
