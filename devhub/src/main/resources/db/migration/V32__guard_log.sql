-- W3-6: Prompt 注入防护审计（正则第一道 + 小模型第二道的拦截/误判回流依据）
CREATE TABLE ai_guard_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  kind VARCHAR(10) NOT NULL COMMENT 'input/output',
  verdict VARCHAR(12) NOT NULL COMMENT 'blocked/suspect/error/leak',
  source VARCHAR(8) NOT NULL COMMENT 'regex/llm/canary',
  hits VARCHAR(500) NULL,
  excerpt VARCHAR(200) NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_guard_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
