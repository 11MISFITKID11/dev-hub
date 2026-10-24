-- 问答反馈闭环：助手消息点赞/点踩（1 有用 / -1 无用），点踩联动知识缺口
ALTER TABLE message ADD COLUMN feedback TINYINT NULL COMMENT '用户反馈：1有用 / -1无用';
