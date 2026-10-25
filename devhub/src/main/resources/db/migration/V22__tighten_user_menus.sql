-- 客户角色收紧：工作台/运营报表聚合的是全局工单与用量数据，属坐席/管理员视角
-- 普通客户（USER）仅保留：智能问答、会话记录、我的工单、知识库
DELETE FROM sys_role_menu WHERE role_id = 2 AND menu_id IN (1, 10);
