-- 用量按（日期,用户,模型）幂等累加
ALTER TABLE ai_usage ADD UNIQUE KEY uk_usage (stat_date, username, model);
