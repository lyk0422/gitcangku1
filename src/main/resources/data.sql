-- 初始化单行仓库版本号：初始版本为 0；脚本可重复执行（测试多上下文共用同名内存库）。
INSERT INTO repository_state (id, version)
SELECT 1, 0 WHERE NOT EXISTS (SELECT 1 FROM repository_state WHERE id = 1);

-- 合成演示数据：root:1(2B) 依赖 a[1,2] 与 b[1,1]；a:2(9B)/a:1(5B) 均依赖 b[1,1]；b:1(3B)。
-- 预算 10 字节锁定时 a:2 组合 2+9+3=14 超预算，回退 a:1，总和 2+5+3=10；共享 b 只计一次。
-- 仅当表为空时插入，避免测试清理后重复种子或与业务数据冲突。
INSERT INTO artifact (name, version, withdrawn, size_bytes, created_at)
SELECT 'root', 1, 0, 2, CURRENT_TIMESTAMP(6) WHERE NOT EXISTS (SELECT 1 FROM artifact);
INSERT INTO artifact (name, version, withdrawn, size_bytes, created_at)
SELECT 'a', 2, 0, 9, CURRENT_TIMESTAMP(6) WHERE NOT EXISTS (SELECT 1 FROM artifact WHERE name = 'a');
INSERT INTO artifact (name, version, withdrawn, size_bytes, created_at)
SELECT 'a', 1, 0, 5, CURRENT_TIMESTAMP(6) WHERE NOT EXISTS (
    SELECT 1 FROM artifact WHERE name = 'a' AND version = 1);
INSERT INTO artifact (name, version, withdrawn, size_bytes, created_at)
SELECT 'b', 1, 0, 3, CURRENT_TIMESTAMP(6) WHERE NOT EXISTS (SELECT 1 FROM artifact WHERE name = 'b');

INSERT INTO artifact_dependency (artifact_id, name, minimum_version, maximum_version)
SELECT id, 'a', 1, 2 FROM artifact WHERE name = 'root' AND version = 1
    AND NOT EXISTS (SELECT 1 FROM artifact_dependency d WHERE d.artifact_id = artifact.id);
INSERT INTO artifact_dependency (artifact_id, name, minimum_version, maximum_version)
SELECT id, 'b', 1, 1 FROM artifact WHERE name = 'root' AND version = 1
    AND NOT EXISTS (SELECT 1 FROM artifact_dependency d WHERE d.artifact_id = artifact.id AND d.name = 'b');
INSERT INTO artifact_dependency (artifact_id, name, minimum_version, maximum_version)
SELECT id, 'b', 1, 1 FROM artifact WHERE name = 'a' AND version = 2
    AND NOT EXISTS (SELECT 1 FROM artifact_dependency d WHERE d.artifact_id = artifact.id);
INSERT INTO artifact_dependency (artifact_id, name, minimum_version, maximum_version)
SELECT id, 'b', 1, 1 FROM artifact WHERE name = 'a' AND version = 1
    AND NOT EXISTS (SELECT 1 FROM artifact_dependency d WHERE d.artifact_id = artifact.id);
