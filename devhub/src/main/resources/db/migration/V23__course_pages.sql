-- 对标课程项目的运营页面补齐：
-- 1) AI 模型配置（DB 化：启用/主模型/参数可热更新，密钥留空继承环境变量）
-- 2) 会话记录评价（管理员侧问答质量审视入口）
-- 3) 质检统计分析（独立看板）
-- 4) 客户角色收紧：知识库不面向客户（门户只留 问答/会话/工单）

CREATE TABLE ai_model_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(64) NOT NULL COMMENT '模型别名（路由用，唯一）',
  provider VARCHAR(32) NOT NULL DEFAULT 'other' COMMENT 'zhipu/deepseek/qwen/other',
  model VARCHAR(64) NOT NULL COMMENT '供应商侧模型ID',
  base_url VARCHAR(255) NOT NULL,
  api_key VARCHAR(512) DEFAULT NULL COMMENT '留空=继承环境变量密钥（推荐）',
  temperature DOUBLE PRECISION DEFAULT 0.7,
  max_tokens INT DEFAULT 4096,
  enabled TINYINT NOT NULL DEFAULT 1,
  is_primary TINYINT NOT NULL DEFAULT 0,
  remark VARCHAR(255) DEFAULT NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_name (name)
) COMMENT 'AI 模型配置（运行时叠加在环境变量配置之上）';

-- 种子：与 application.yml 保持一致；密钥留空走环境变量，密钥永不入库
INSERT INTO ai_model_config (name, provider, model, base_url, temperature, max_tokens, enabled, is_primary, remark) VALUES
  ('glm-4-flash', 'zhipu', 'glm-4-flash', 'https://open.bigmodel.cn/api/paas/v4', 0.7, 4096, 1, 1, '智谱免费快速模型（默认）'),
  ('glm-4-air',   'zhipu', 'glm-4-air',   'https://open.bigmodel.cn/api/paas/v4', 0.7, 4096, 1, 0, '智谱轻量模型（降级首选）'),
  ('glm-4-plus',  'zhipu', 'glm-4-plus',  'https://open.bigmodel.cn/api/paas/v4', 0.7, 4096, 1, 0, '智谱旗舰模型'),
  ('deepseek-chat', 'deepseek', 'deepseek-chat', 'https://api.deepseek.com/v1', 0.7, 8192, 1, 0, 'DeepSeek 对话模型');

-- 新菜单：会话评价（管理员）、质检统计（管理员）、模型配置（系统管理下）
INSERT INTO sys_menu (id, parent_id, name, path, perm, sort, menu_type) VALUES
  (26, 6,  '会话评价', '/ai/feedback',     'ai:feedback', 68, 'C'),
  (27, 6,  '质检统计', '/tickets/qc',      'ticket:qc',   69, 'C'),
  (28, 11, '模型配置', '/system/models',   'system:model', 52, 'C');
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 26), (1, 27), (1, 28);

-- 客户门户只有 问答/会话/工单，知识库转为内部运营能力
DELETE FROM sys_role_menu WHERE role_id = 2 AND menu_id = 5;
