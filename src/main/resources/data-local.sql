-- 本地合成数据：主体 demo-subject，RESEARCH 用途 epoch 1 已授权并预置 2 条记录与 10 个查询额度；
-- 账本从首次真实业务操作起追加（updated_seq=0 表示种子数据，真实账本 seq 从 1 开始）。
MERGE INTO consent_grant (subject_key, purpose, epoch, status, request_id, created_at, revoked_at)
KEY (subject_key, purpose, epoch)
VALUES ('demo-subject', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-demo-research-1', CURRENT_TIMESTAMP, NULL);

MERGE INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id, created_at)
KEY (subject_key, purpose, epoch, record_key)
VALUES ('demo-subject', 'RESEARCH', 1, 'rec-a', 'demo-payload-a', 'seed-write-demo-rec-a', CURRENT_TIMESTAMP);

MERGE INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id, created_at)
KEY (subject_key, purpose, epoch, record_key)
VALUES ('demo-subject', 'RESEARCH', 1, 'rec-b', 'demo-payload-b', 'seed-write-demo-rec-b', CURRENT_TIMESTAMP);

MERGE INTO purpose_quota (subject_key, purpose, epoch, balance, status, updated_seq,
                          created_at_utc, updated_at_utc)
KEY (subject_key, purpose, epoch)
VALUES ('demo-subject', 'RESEARCH', 1, 10, 'ACTIVE', 0,
        CAST('2026-09-29 00:00:00.000000' AS TIMESTAMP(6)),
        CAST('2026-09-29 00:00:00.000000' AS TIMESTAMP(6)));
