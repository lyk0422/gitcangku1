-- 本地演示合成数据：两个已放行（RELEASED）、均含过敏原“乳”、处于同一有效清洁代次 3 的父批，
-- 可直接复现“父批 A、B 均含乳且使用清洁代次 3 可合批”。仅用于本地 H2 内存库启动初始化，
-- 幂等（重复执行不重复插入）；测试在每个用例前清空全部表，因此不影响标准 test 断言；JVM 退出后数据不保留。
INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status, cleaning_generation, created_at)
SELECT 'PA-MILK-A', 'P-MILK', 'LOT-A-20260930', '2026-09-30T01:00:00Z', 'RELEASED', 3, '2026-09-30T02:00:00Z'
WHERE NOT EXISTS (SELECT 1 FROM batch WHERE batch_key = 'PA-MILK-A');
INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status, cleaning_generation, created_at)
SELECT 'PA-MILK-B', 'P-MILK', 'LOT-B-20260930', '2026-09-30T01:05:00Z', 'RELEASED', 3, '2026-09-30T02:05:00Z'
WHERE NOT EXISTS (SELECT 1 FROM batch WHERE batch_key = 'PA-MILK-B');

INSERT INTO batch_required_test (batch_key, test_item, seq)
SELECT 'PA-MILK-A', '微生物', 1 WHERE NOT EXISTS (
    SELECT 1 FROM batch_required_test WHERE batch_key = 'PA-MILK-A' AND test_item = '微生物');
INSERT INTO batch_required_test (batch_key, test_item, seq)
SELECT 'PA-MILK-B', '微生物', 1 WHERE NOT EXISTS (
    SELECT 1 FROM batch_required_test WHERE batch_key = 'PA-MILK-B' AND test_item = '微生物');

INSERT INTO batch_allergen (batch_key, allergen, seq)
SELECT 'PA-MILK-A', '乳', 1 WHERE NOT EXISTS (
    SELECT 1 FROM batch_allergen WHERE batch_key = 'PA-MILK-A' AND allergen = '乳');
INSERT INTO batch_allergen (batch_key, allergen, seq)
SELECT 'PA-MILK-B', '乳', 1 WHERE NOT EXISTS (
    SELECT 1 FROM batch_allergen WHERE batch_key = 'PA-MILK-B' AND allergen = '乳');

INSERT INTO test_result (batch_key, test_key, test_item, outcome, inspector, created_at)
SELECT 'PA-MILK-A', 'TR-A-1', '微生物', 'PASS', 'inspect-a', '2026-09-30T02:10:00Z'
WHERE NOT EXISTS (SELECT 1 FROM test_result WHERE batch_key = 'PA-MILK-A' AND test_key = 'TR-A-1');
INSERT INTO test_result (batch_key, test_key, test_item, outcome, inspector, created_at)
SELECT 'PA-MILK-B', 'TR-B-1', '微生物', 'PASS', 'inspect-b', '2026-09-30T02:15:00Z'
WHERE NOT EXISTS (SELECT 1 FROM test_result WHERE batch_key = 'PA-MILK-B' AND test_key = 'TR-B-1');

INSERT INTO approval (batch_key, command_key, actor_id, role, seq, created_at)
SELECT 'PA-MILK-A', 'SEED-A-Q', 'qa-a', 'QUALITY', 1, '2026-09-30T02:20:00Z'
WHERE NOT EXISTS (SELECT 1 FROM approval WHERE batch_key = 'PA-MILK-A' AND command_key = 'SEED-A-Q');
INSERT INTO approval (batch_key, command_key, actor_id, role, seq, created_at)
SELECT 'PA-MILK-A', 'SEED-A-O', 'ops-a', 'OPERATIONS', 2, '2026-09-30T02:21:00Z'
WHERE NOT EXISTS (SELECT 1 FROM approval WHERE batch_key = 'PA-MILK-A' AND command_key = 'SEED-A-O');
INSERT INTO approval (batch_key, command_key, actor_id, role, seq, created_at)
SELECT 'PA-MILK-B', 'SEED-B-Q', 'qa-b', 'QUALITY', 1, '2026-09-30T02:25:00Z'
WHERE NOT EXISTS (SELECT 1 FROM approval WHERE batch_key = 'PA-MILK-B' AND command_key = 'SEED-B-Q');
INSERT INTO approval (batch_key, command_key, actor_id, role, seq, created_at)
SELECT 'PA-MILK-B', 'SEED-B-O', 'ops-b', 'OPERATIONS', 2, '2026-09-30T02:26:00Z'
WHERE NOT EXISTS (SELECT 1 FROM approval WHERE batch_key = 'PA-MILK-B' AND command_key = 'SEED-B-O');
