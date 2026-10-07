-- 本地入口合成演示数据（全部为合成字符串，非真实数据）：
-- 一个有效授权代与两条合成记录，便于本地接口联调；重复执行不重复插入
INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)
SELECT 'demo-subject', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-demo-1' FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM consent_grant WHERE subject_key = 'demo-subject' AND purpose = 'RESEARCH' AND epoch = 1
);

INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id)
SELECT 'demo-subject', 'RESEARCH', 1, 'demo-rec-1', 'demo-payload-1', 'seed-write-demo-1' FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM consent_record
    WHERE subject_key = 'demo-subject' AND purpose = 'RESEARCH' AND epoch = 1 AND record_key = 'demo-rec-1'
);

INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id)
SELECT 'demo-subject', 'RESEARCH', 1, 'demo-rec-2', 'demo-payload-2', 'seed-write-demo-2' FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM consent_record
    WHERE subject_key = 'demo-subject' AND purpose = 'RESEARCH' AND epoch = 1 AND record_key = 'demo-rec-2'
);
