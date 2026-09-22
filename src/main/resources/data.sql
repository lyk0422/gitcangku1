-- 初始化单行仓库版本号：初始版本为 0；脚本可重复执行（测试多上下文共用同名内存库）。
INSERT INTO repository_state (id, version)
SELECT 1, 0 WHERE NOT EXISTS (SELECT 1 FROM repository_state WHERE id = 1);
