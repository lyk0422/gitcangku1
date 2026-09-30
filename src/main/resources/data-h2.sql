-- 本地 H2 合成种子数据（UTC 时间字面值）：主体 demo-subject 的 AUDIT 用途前 3 代已撤回，第 4 代有效
INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id, created_at, revoked_at)
VALUES ('demo-subject', 'AUDIT', 1, 'REVOKED', 'seed-g-1', TIMESTAMP '2026-09-01 00:00:00', TIMESTAMP '2026-09-02 00:00:00');
INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id, created_at, revoked_at)
VALUES ('demo-subject', 'AUDIT', 2, 'REVOKED', 'seed-g-2', TIMESTAMP '2026-09-03 00:00:00', TIMESTAMP '2026-09-04 00:00:00');
INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id, created_at, revoked_at)
VALUES ('demo-subject', 'AUDIT', 3, 'REVOKED', 'seed-g-3', TIMESTAMP '2026-09-05 00:00:00', TIMESTAMP '2026-09-06 00:00:00');
INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id, created_at, revoked_at)
VALUES ('demo-subject', 'AUDIT', 4, 'ACTIVE', 'seed-g-4', TIMESTAMP '2026-09-07 00:00:00', NULL);

INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id, created_at)
VALUES ('demo-subject', 'AUDIT', 4, 'rec-1', 'audit-only-payload-epoch-4', 'seed-w-1', TIMESTAMP '2026-09-07 00:01:00');
