-- 初始化单行仓库版本号：初始版本为 0；脚本可重复执行（测试多上下文共用同名内存库）。
INSERT INTO repository_state (id, version)
SELECT 1, 0 WHERE NOT EXISTS (SELECT 1 FROM repository_state WHERE id = 1);

-- 合成验收数据（幂等，可重复执行）：
-- 制品 A:1 依赖 X@1.0（记为 X 版本 1，闭区间 [1,1]），X:1 风险 HIGH；
-- 另备 A:2 依赖 X@1.1（X 版本 2）与 HIGH 风险的 X:2，用于验证豁免不跨版本。
-- 四次制品登记对应仓库版本号 4。
INSERT INTO artifact (name, version, withdrawn, risk_level, created_at)
SELECT 'A', 1, 0, 'NONE', CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM artifact WHERE name = 'A' AND version = 1);
INSERT INTO artifact (name, version, withdrawn, risk_level, created_at)
SELECT 'A', 2, 0, 'NONE', CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM artifact WHERE name = 'A' AND version = 2);
INSERT INTO artifact (name, version, withdrawn, risk_level, created_at)
SELECT 'X', 1, 0, 'HIGH', CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM artifact WHERE name = 'X' AND version = 1);
INSERT INTO artifact (name, version, withdrawn, risk_level, created_at)
SELECT 'X', 2, 0, 'HIGH', CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM artifact WHERE name = 'X' AND version = 2);

INSERT INTO artifact_dependency (artifact_id, name, minimum_version, maximum_version)
SELECT a.id, 'X', 1, 1 FROM artifact a
WHERE a.name = 'A' AND a.version = 1
  AND NOT EXISTS (SELECT 1 FROM artifact_dependency d
                  WHERE d.artifact_id = a.id AND d.name = 'X');
INSERT INTO artifact_dependency (artifact_id, name, minimum_version, maximum_version)
SELECT a.id, 'X', 2, 2 FROM artifact a
WHERE a.name = 'A' AND a.version = 2
  AND NOT EXISTS (SELECT 1 FROM artifact_dependency d
                  WHERE d.artifact_id = a.id AND d.name = 'X');

-- 仅在尚处于初始化版本 0 时对齐为合成数据应有的仓库版本号，避免覆盖测试写入的状态。
UPDATE repository_state SET version = 4 WHERE id = 1 AND version = 0
    AND (SELECT COUNT(1) FROM artifact) >= 4;
