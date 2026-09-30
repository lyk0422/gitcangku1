-- 本地合成数据：授权方已为主体 subj-a 的 AUDIT 用途授予 epoch 1，并写入一条审计可读记录。
-- 接收方承诺需在运行时通过 /api/v1/recipient/attestations 提交。
INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)
VALUES ('subj-a', 'AUDIT', 1, 'ACTIVE', 'seed-grant-audit-1');

INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id)
VALUES ('subj-a', 'AUDIT', 1, 'rec-1', 'audit-payload-1', 'seed-record-audit-1');
