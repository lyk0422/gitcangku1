-- 本地入口合成演示数据：主体 subj-demo 的 RESEARCH 授权（第 1 代）与两条记录，均为合成字符串
-- 幂等写法：已存在时不重复插入，应用重启可安全重放
INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)
SELECT 'subj-demo', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-1' FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM consent_grant WHERE subject_key = 'subj-demo' AND purpose = 'RESEARCH' AND epoch = 1
);

INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id)
SELECT 'subj-demo', 'RESEARCH', 1, 'rec-001', 'demo-payload-001', 'seed-write-1' FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM consent_record WHERE subject_key = 'subj-demo' AND purpose = 'RESEARCH' AND epoch = 1 AND record_key = 'rec-001'
);

INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id)
SELECT 'subj-demo', 'RESEARCH', 1, 'rec-002', 'demo-payload-002', 'seed-write-2' FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM consent_record WHERE subject_key = 'subj-demo' AND purpose = 'RESEARCH' AND epoch = 1 AND record_key = 'rec-002'
);
