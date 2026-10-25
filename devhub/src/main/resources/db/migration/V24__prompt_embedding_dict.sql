-- 对标课程功能清单补齐：Prompt 模板管理 / 向量模型配置 / 工单分类维护
-- 1) Prompt 模板：表存"覆盖值"，代码内置默认值兜底（库为空 = 全部走默认）
CREATE TABLE prompt_template (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  scene VARCHAR(64) NOT NULL COMMENT '场景键：chat_answer/query_rewrite/ticket_classify/...',
  name VARCHAR(128) NOT NULL COMMENT '场景说明名',
  content LONGTEXT NOT NULL COMMENT '模板正文，{var} 占位符',
  update_by VARCHAR(64),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_scene (scene)
) COMMENT 'Prompt 模板（覆盖内置默认，改完即生效）';

-- 2) 向量模型配置：复用 ai_model_config，区分 chat / embedding
ALTER TABLE ai_model_config
  ADD COLUMN model_type VARCHAR(12) NOT NULL DEFAULT 'chat' COMMENT 'chat=对话模型 embedding=向量模型',
  ADD COLUMN dimensions INT NULL COMMENT '向量维度（须与 Milvus 集合一致）';

INSERT INTO ai_model_config (name, provider, model, base_url, temperature, max_tokens, enabled, is_primary, model_type, dimensions, remark) VALUES
  ('embedding-3', 'zhipu', 'embedding-3', 'https://open.bigmodel.cn/api/paas/v4', 0.7, NULL, 1, 0, 'embedding', 1024,
   '向量模型（维度须与 Milvus 集合一致；密钥留空继承环境变量）');

-- 3) 工单分类字典化（TicketAiService 分类提示词与校验集、前端映射统一消费）
INSERT INTO sys_dict_data (type, label, value, sort) VALUES
  ('ticket_category', '产品咨询', 'PRODUCT', 1),
  ('ticket_category', '缺陷故障', 'BUG', 2),
  ('ticket_category', '账号权限', 'ACCOUNT', 3),
  ('ticket_category', '计费账单', 'BILLING', 4),
  ('ticket_category', '使用帮助', 'USAGE', 5),
  ('ticket_category', '其他', 'OTHER', 6);

-- 4) Prompt 模板管理菜单（系统管理下，仅管理员）
INSERT INTO sys_menu (id, parent_id, name, path, perm, sort, menu_type) VALUES
  (29, 11, 'Prompt 模板', '/system/prompts', 'system:prompt', 53, 'C');
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 29);
