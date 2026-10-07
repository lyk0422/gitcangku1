-- 本地入口合成演示数据：一个主体在 RESEARCH 用途下的有效授权与两条记录（均为合成字符串）
INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)
VALUES ('demo-subject', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-1');

INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id)
VALUES ('demo-subject', 'RESEARCH', 1, 'demo-rec-1', 'demo-payload-1', 'seed-write-1');

INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id)
VALUES ('demo-subject', 'RESEARCH', 1, 'demo-rec-2', 'demo-payload-2', 'seed-write-2');
