-- 产品转型：DevHub 研发管理系统 → HelpHub · AI 智能客服与工单处理系统
-- 定位：AI 客服(RAG 问答) + 人工工单双轨；研发域菜单整体下线（后端能力保留在代码中）

-- 1) 移除研发域菜单（研发协作组/项目/任务/缺陷/需求管理）
DELETE FROM sys_role_menu WHERE menu_id IN (22, 2, 3, 4, 13);
DELETE FROM sys_menu WHERE id IN (22, 2, 3, 4, 13);

-- 2) 一级信息架构重组（客服优先）
UPDATE sys_menu SET name = '智能客服', sort = 1 WHERE id = 6;   -- 原「知识中枢」
UPDATE sys_menu SET name = '知识库',   sort = 2 WHERE id = 5;   -- 原「文档中心」
UPDATE sys_menu SET sort = 3 WHERE id = 1;                       -- 工作台（客服工作台）
UPDATE sys_menu SET name = '运营报表', sort = 4 WHERE id = 10;   -- 原「报表统计」（AI 用量）
UPDATE sys_menu SET sort = 5 WHERE id = 11;                      -- 系统管理

-- 3) 二级菜单对齐客服域话术
UPDATE sys_menu SET name = '智能问答' WHERE id = 7;   -- 原「对话问答」
UPDATE sys_menu SET name = '会话记录' WHERE id = 8;   -- 原「会话历史」
UPDATE sys_menu SET name = '质检回归' WHERE id = 23;  -- 原「AI 评测」（RAG 质量回归）
