-- 评测集与回归看板（对标 RAGAS/Langfuse 的检索质量回归思路）
CREATE TABLE eval_case (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  question VARCHAR(500) NOT NULL,
  expect_doc_name VARCHAR(255) NULL COMMENT '期望命中的文档名（包含匹配，判命中率）',
  expect_keywords VARCHAR(500) NULL COMMENT '期望答案包含的关键词，逗号分隔',
  enabled TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_enabled (enabled)
);

CREATE TABLE eval_run (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  mode VARCHAR(10) NOT NULL COMMENT 'retrieval=仅检索(免费) / full=真实问答链路(含忠实度)',
  total INT NOT NULL,
  hit_count INT NOT NULL COMMENT '期望文档进入溯源的用例数',
  gate_pass INT NOT NULL COMMENT '门控通过（未拒答）的用例数',
  avg_top1 DOUBLE COMMENT '重排 top1 平均分',
  avg_faith DOUBLE NULL COMMENT '忠实度均值（full 模式）',
  cost_ms BIGINT NOT NULL,
  created_by VARCHAR(64),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE eval_result (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id BIGINT NOT NULL,
  case_id BIGINT NOT NULL,
  question VARCHAR(500) NOT NULL,
  hit TINYINT NOT NULL,
  gate_pass TINYINT NOT NULL,
  top1_score DOUBLE,
  top_docs VARCHAR(1000) COMMENT '重排 Top5 文件名（截断展示）',
  faith_score DOUBLE NULL,
  answer_excerpt VARCHAR(500) NULL,
  KEY idx_run (run_id)
);

INSERT INTO sys_menu (id, parent_id, name, path, perm, sort, menu_type) VALUES
  (23, 6, 'AI 评测', '/ai/eval', 'ai:eval', 65, 'C');
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, id FROM sys_menu WHERE id = 23;
