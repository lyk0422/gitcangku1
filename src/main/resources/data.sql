-- 初始化单行仓库版本号：初始版本为 0；脚本可重复执行（测试多上下文共用同名内存库）。
INSERT INTO repository_state (id, version)
SELECT 1, 0 WHERE NOT EXISTS (SELECT 1 FROM repository_state WHERE id = 1);

-- 合成验收数据：制品 A:1 声明依赖 X[1,1]，X:1 风险 HIGH；两次登记后仓库版本为 2。
-- 仅用于本地可复现演示；H2 内存库随 JVM 退出清空，测试上下文会在用例前清理这些数据。
INSERT INTO artifact (name, version, withdrawn, created_at)
SELECT 'A', 1, 0, TIMESTAMP '2026-09-30 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM artifact WHERE name = 'A' AND version = 1);

INSERT INTO artifact (name, version, withdrawn, created_at)
SELECT 'X', 1, 0, TIMESTAMP '2026-09-30 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM artifact WHERE name = 'X' AND version = 1);

INSERT INTO artifact_dependency (artifact_id, name, minimum_version, maximum_version)
SELECT a.id, 'X', 1, 1
FROM artifact a
WHERE a.name = 'A' AND a.version = 1
  AND NOT EXISTS (
      SELECT 1 FROM artifact_dependency d
      JOIN artifact a2 ON a2.id = d.artifact_id
      WHERE a2.name = 'A' AND a2.version = 1 AND d.name = 'X');

INSERT INTO artifact_risk (name, version, risk_level, reason, created_at, updated_at)
SELECT 'X', 1, 'HIGH', '合成高危依赖（演示用）',
       TIMESTAMP '2026-09-30 00:00:00', TIMESTAMP '2026-09-30 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM artifact_risk WHERE name = 'X' AND version = 1);

UPDATE repository_state SET version = 2 WHERE id = 1 AND version = 0;
