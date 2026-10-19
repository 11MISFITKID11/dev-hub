-- 演示数据域：正式知识库文档设为公共（全部门可见），验收测试文档保留部门归属
-- 注意用 ASCII 模式匹配（中文 LIKE 经 docker cp 会被编码破坏）
UPDATE document SET dept_id = NULL WHERE file_name LIKE '%.pdf' OR file_name LIKE '%Arthas%';
