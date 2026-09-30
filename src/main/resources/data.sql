-- 本地启动合成演示数据（H2 内存库）；使用 MERGE 保证幂等，重启不产生重复键冲突。
-- 测试使用独立命名内存库且仅按各自唯一键断言，本种子不影响测试结果。
MERGE INTO evidence
    (evidence_key, case_key, category, seal_no, custodian_id, status, created_at, updated_at)
KEY (evidence_key)
VALUES ('EV-DEMO-A', 'CASE-DEMO', 'DOCUMENT', 'SEAL-DEMO-A', 'alice', 'SEALED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

MERGE INTO evidence
    (evidence_key, case_key, category, seal_no, custodian_id, status, created_at, updated_at)
KEY (evidence_key)
VALUES ('EV-DEMO-B', 'CASE-DEMO', 'DOCUMENT', 'SEAL-DEMO-B', 'alice', 'SEALED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
