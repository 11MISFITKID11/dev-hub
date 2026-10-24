-- 移除 AI 应用开放能力（产品决策：应用分发不符合项目定位）
DROP TABLE IF EXISTS api_key;
DROP TABLE IF EXISTS ai_app;

-- 保留菜单重排（知识中枢主业/研发协作分组），仅移除「AI 应用」入口
DELETE FROM sys_role_menu WHERE menu_id = 21;
DELETE FROM sys_menu WHERE id = 21;
