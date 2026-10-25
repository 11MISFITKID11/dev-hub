-- W2-3: Trace 落库（替代内存 Map：重启可查、多实例可查）
CREATE TABLE ai_trace (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  trace_id VARCHAR(32) NOT NULL,
  operation VARCHAR(64) NOT NULL,
  duration_ms INT NULL,
  span_count INT NULL,
  spans TEXT NULL COMMENT 'span 序列 JSON（name/timestamp/attributes）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_trace_time (create_time),
  INDEX idx_trace_id (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
