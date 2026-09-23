-- 初始化单行仓库版本号与策略版本号：初始均为 0；脚本可重复执行（测试多上下文共用同名内存库）。
INSERT INTO repository_state (id, version)
SELECT 1, 0 WHERE NOT EXISTS (SELECT 1 FROM repository_state WHERE id = 1);

INSERT INTO policy_state (id, current_version)
SELECT 1, 0 WHERE NOT EXISTS (SELECT 1 FROM policy_state WHERE id = 1);
