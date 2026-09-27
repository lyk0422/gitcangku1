-- 合成演示数据：H2(MySQL 模式) 启动时自动初始化；全部幂等，可重复执行。
-- 案件：CASE-A（保管人 alice）、CASE-B（保管人 bob）；移交令 ORDER-1 版本 v1 长期有效。
-- 两件在库、封签正常的证物 EV-DEMO-1/2 归属 CASE-A，可直接用于一条跨案移交合成调用。

MERGE INTO case_custodian (case_key, custodian_id, created_at)
KEY (case_key, custodian_id) VALUES ('CASE-A', 'alice', '2026-09-27 00:00:00');
MERGE INTO case_custodian (case_key, custodian_id, created_at)
KEY (case_key, custodian_id) VALUES ('CASE-B', 'bob', '2026-09-27 00:00:00');

MERGE INTO transfer_order
    (order_key, order_version, from_case_key, to_case_key, effective_from, effective_to, created_at)
KEY (order_key, order_version)
VALUES ('ORDER-1', 'v1', 'CASE-A', 'CASE-B', '2026-01-01 00:00:00', NULL, '2026-09-27 00:00:00');

INSERT INTO evidence
    (evidence_key, case_key, category, seal_no, location, custodian_id, status, created_at, updated_at)
SELECT 'EV-DEMO-1', 'CASE-A', 'DOCUMENT', 'SEAL-A-1', 'VAULT-A-01', 'alice', 'SEALED',
       '2026-09-27 00:00:00', '2026-09-27 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM evidence WHERE evidence_key = 'EV-DEMO-1');

INSERT INTO evidence
    (evidence_key, case_key, category, seal_no, location, custodian_id, status, created_at, updated_at)
SELECT 'EV-DEMO-2', 'CASE-A', 'DOCUMENT', 'SEAL-A-2', NULL, 'alice', 'SEALED',
       '2026-09-27 00:00:00', '2026-09-27 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM evidence WHERE evidence_key = 'EV-DEMO-2');
