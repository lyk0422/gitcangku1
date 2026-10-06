-- 本地入口合成数据：A 代 1 值 2、B 代 1 值 3、D 代 1 值 4，及目标主体 C、E 的当前研究授权。
-- 可复现合成调用：由 A、B 派生 C（值 5），再由 C、D 派生 E（值 9）。
INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id) VALUES
    ('subj-a', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-a'),
    ('subj-b', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-b'),
    ('subj-c', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-c'),
    ('subj-d', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-d'),
    ('subj-e', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-e');

INSERT INTO numeric_record (subject_key, epoch, record_key, kind, record_value, depth, request_id) VALUES
    ('subj-a', 1, 'n1', 'NUMERIC', 2, 0, 'seed-write-a'),
    ('subj-b', 1, 'n1', 'NUMERIC', 3, 0, 'seed-write-b'),
    ('subj-d', 1, 'n1', 'NUMERIC', 4, 0, 'seed-write-d');
