-- V14 自动化规则 + 工时登记（P2 补齐；IM Webhook 通知渠道按范围决策不做）

-- ---------- 自动化规则（Jira Automation lite：任务事件 → 动作） ----------
CREATE TABLE prj_automation_rule (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id    BIGINT NOT NULL,
  name          VARCHAR(128) NOT NULL,
  -- 触发器：TASK_CREATED / STATUS_CHANGED(值=* 或指定状态) / ASSIGNEE_CHANGED
  trigger_type  VARCHAR(32) NOT NULL,
  trigger_value VARCHAR(64) DEFAULT '*' COMMENT 'STATUS_CHANGED 时的目标状态，* 为任意',
  -- 动作：NOTIFY(收件人=OWNER/ASSIGNEE/REPORTER/用户名) / AUTO_ASSIGN(值=用户名) / SET_PRIORITY(值=L/M/H/U)
  action_type   VARCHAR(32) NOT NULL,
  action_value  VARCHAR(255),
  enabled       INT DEFAULT 1,
  deleted       INT DEFAULT 0,
  create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_proj (project_id, enabled)
) COMMENT '项目自动化规则';

-- ---------- 工时登记 ----------
CREATE TABLE prj_worklog (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id    BIGINT NOT NULL,
  username   VARCHAR(64) NOT NULL,
  hours      DECIMAL(5,1) NOT NULL COMMENT '本次投入小时',
  work_date  DATE NOT NULL,
  note       VARCHAR(500),
  deleted    INT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_task (task_id),
  KEY idx_user_date (username, work_date)
) COMMENT '任务工时流水';
