-- 本地启动合成演示数据：两个隔离中的批次（产品 PROD-A / PROD-B），用于生产线登记与清洗验证演示。
-- 幂等写法（派生表 + WHERE NOT EXISTS），重复初始化不产生重复键错误；H2 与 MySQL 均可执行。
INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status, created_at)
SELECT * FROM (
    SELECT 'DEMO-A-001' AS batch_key, 'PROD-A' AS product_code, 'LOT-A-001' AS batch_no,
           '2026-01-02T03:04:05Z' AS produced_at, 'QUARANTINED' AS status,
           '2026-01-02T03:04:05Z' AS created_at
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM batch WHERE batch_key = 'DEMO-A-001');

INSERT INTO batch_required_test (batch_key, test_item, seq)
SELECT * FROM (SELECT 'DEMO-A-001' AS batch_key, '外观' AS test_item, 1 AS seq) AS seed
WHERE NOT EXISTS (SELECT 1 FROM batch_required_test WHERE batch_key = 'DEMO-A-001');

INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status, created_at)
SELECT * FROM (
    SELECT 'DEMO-B-001' AS batch_key, 'PROD-B' AS product_code, 'LOT-B-001' AS batch_no,
           '2026-01-03T03:04:05Z' AS produced_at, 'QUARANTINED' AS status,
           '2026-01-03T03:04:05Z' AS created_at
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM batch WHERE batch_key = 'DEMO-B-001');

INSERT INTO batch_required_test (batch_key, test_item, seq)
SELECT * FROM (SELECT 'DEMO-B-001' AS batch_key, '外观' AS test_item, 1 AS seq) AS seed
WHERE NOT EXISTS (SELECT 1 FROM batch_required_test WHERE batch_key = 'DEMO-B-001');