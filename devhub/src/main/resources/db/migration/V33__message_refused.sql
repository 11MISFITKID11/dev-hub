-- 走查修复：拒答标记持久化（历史会话恢复"转工单"入口）+ 空字段兜底
ALTER TABLE message ADD COLUMN refused TINYINT NOT NULL DEFAULT 0 COMMENT '该回答为检索拒答（历史恢复转单入口）';
