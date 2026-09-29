-- 本地合成种子数据（仅 local profile 的 H2 内存库通过 data-locations 显式加载）。
-- 默认 profile 不加载本文件；JVM 退出后内存数据丢失，不做跨进程持久化。
-- 主体 subj-demo：RESEARCH 用途 epoch1 有效，已声明 100 个对象的查询额度，含两条合成记录。
INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id, created_at)
VALUES ('subj-demo', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-research-1', CURRENT_TIMESTAMP);

INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id, created_at)
VALUES ('subj-demo', 'RESEARCH', 1, 'rec-1', 'synthetic-payload-1', 'seed-write-1', CURRENT_TIMESTAMP),
       ('subj-demo', 'RESEARCH', 1, 'rec-2', 'synthetic-payload-2', 'seed-write-2', CURRENT_TIMESTAMP);

INSERT INTO purpose_quota (subject_key, purpose, epoch, quota_limit, used_amount, request_id,
                           created_at_utc, updated_at_utc)
VALUES ('subj-demo', 'RESEARCH', 1, 100, 0, 'seed-quota-1',
        TIMESTAMP '2026-09-29 00:00:00', TIMESTAMP '2026-09-29 00:00:00');
