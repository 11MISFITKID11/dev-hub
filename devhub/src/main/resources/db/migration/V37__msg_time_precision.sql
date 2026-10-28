-- 坐席实时会话：消息时序精度升级到毫秒（秒级 DATETIME 下同秒多条消息排序不稳定，
-- 实时对话场景客户/坐席/系统消息常在同一秒内交错）
ALTER TABLE message MODIFY COLUMN create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
ALTER TABLE conversation MODIFY COLUMN update_time DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3);
