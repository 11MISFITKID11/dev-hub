-- P1-7 通知中心：五类事件（评论@/指派/缺陷转给我/迭代关闭/文档审核结果）
CREATE TABLE sys_notification (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  recipient VARCHAR(64) NOT NULL,
  type VARCHAR(32) NOT NULL COMMENT 'mention/assign/defect/iteration/doc_audit',
  title VARCHAR(200) NOT NULL,
  content VARCHAR(1000),
  ref_url VARCHAR(200),
  is_read TINYINT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_recipient (recipient, is_read)
) COMMENT '站内通知';
