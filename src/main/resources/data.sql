-- 合成演示数据：本地 H2 内存库启动时初始化，三个研究用途主体的数值记录 2、3、4，
-- 用于可复现的群体汇总调用（预期人数 3、总和 9）。仅合成数据，不含真实个人信息。
INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id) VALUES
    ('demo-subj-1', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-1'),
    ('demo-subj-2', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-2'),
    ('demo-subj-3', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-3');

INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id, record_type, numeric_value) VALUES
    ('demo-subj-1', 'RESEARCH', 1, 'metric', '2', 'seed-write-1', 'NUMERIC', 2),
    ('demo-subj-2', 'RESEARCH', 1, 'metric', '3', 'seed-write-2', 'NUMERIC', 3),
    ('demo-subj-3', 'RESEARCH', 1, 'metric', '4', 'seed-write-3', 'NUMERIC', 4);
