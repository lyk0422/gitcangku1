-- 初始化单行仓库版本号：初始版本为 0；脚本可重复执行（测试多上下文共用同名内存库）。
INSERT INTO repository_state (id, version)
SELECT 1, 0 WHERE NOT EXISTS (SELECT 1 FROM repository_state WHERE id = 1);

-- 合成业务数据（幂等插入）：
-- root:1 体积 2，依赖 a[1,2]；a:2 体积 9、a:1 体积 5，二者均依赖共享子制品 b:1（体积 3）。
-- 预算 10 时：root+a2+b = 2+9+3 = 14 超限，回退到 a1：2+5+3 = 10 恰好可行。
-- b 被 a1/a2 两个父依赖，同名只计一次体积（3 字节）。
INSERT INTO artifact (name, version, withdrawn, size_bytes, created_at)
SELECT 'root', 1, 0, 2, CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM artifact WHERE name = 'root' AND version = 1);
INSERT INTO artifact (name, version, withdrawn, size_bytes, created_at)
SELECT 'a', 2, 0, 9, CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM artifact WHERE name = 'a' AND version = 2);
INSERT INTO artifact (name, version, withdrawn, size_bytes, created_at)
SELECT 'a', 1, 0, 5, CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM artifact WHERE name = 'a' AND version = 1);
INSERT INTO artifact (name, version, withdrawn, size_bytes, created_at)
SELECT 'b', 1, 0, 3, CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM artifact WHERE name = 'b' AND version = 1);

INSERT INTO artifact_dependency (artifact_id, name, minimum_version, maximum_version)
SELECT id, 'a', 1, 2 FROM artifact
WHERE name = 'root' AND version = 1
  AND NOT EXISTS (SELECT 1 FROM artifact_dependency d WHERE d.artifact_id = artifact.id AND d.name = 'a');
INSERT INTO artifact_dependency (artifact_id, name, minimum_version, maximum_version)
SELECT id, 'b', 1, 1 FROM artifact
WHERE name = 'a' AND version = 2
  AND NOT EXISTS (SELECT 1 FROM artifact_dependency d WHERE d.artifact_id = artifact.id AND d.name = 'b');
INSERT INTO artifact_dependency (artifact_id, name, minimum_version, maximum_version)
SELECT id, 'b', 1, 1 FROM artifact
WHERE name = 'a' AND version = 1
  AND NOT EXISTS (SELECT 1 FROM artifact_dependency d WHERE d.artifact_id = artifact.id AND d.name = 'b');
