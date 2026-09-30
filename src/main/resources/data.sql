-- 合成演示数据：航次 V-DEMO，站位 S1、S2 各具备 0、50、100 米采纳样本，
-- 另保留一条未采纳重测，演示“原始重测仍保留”。时间均为 UTC。

INSERT INTO voyage (voyage_code, status, sealed_utc, created_utc)
VALUES ('V-DEMO', 'OPEN', NULL, TIMESTAMP '2026-09-01 00:00:00');

-- S1：三条采纳版本 + 一条 0 米重测（未采纳）
INSERT INTO salinity_sample
    (voyage_code, station_code, depth_m, salinity_psu, collected_utc, sample_no, request_id, adopted, superseded_by, created_utc)
VALUES
    ('V-DEMO', 'S1', 0,   35.120, TIMESTAMP '2026-09-01 02:00:00', 'DEMO-S1-0',    'REQ-DEMO-S1-0',    TRUE, NULL, TIMESTAMP '2026-09-01 02:05:00'),
    ('V-DEMO', 'S1', 0,   35.105, TIMESTAMP '2026-09-01 02:10:00', 'DEMO-S1-0-R',  NULL,               FALSE, NULL, TIMESTAMP '2026-09-01 02:15:00'),
    ('V-DEMO', 'S1', 50,  34.780, TIMESTAMP '2026-09-01 02:30:00', 'DEMO-S1-50',   'REQ-DEMO-S1-50',   TRUE, NULL, TIMESTAMP '2026-09-01 02:35:00'),
    ('V-DEMO', 'S1', 100, 34.020, TIMESTAMP '2026-09-01 03:00:00', 'DEMO-S1-100',  'REQ-DEMO-S1-100',  TRUE, NULL, TIMESTAMP '2026-09-01 03:05:00'),
    ('V-DEMO', 'S2', 0,   35.210, TIMESTAMP '2026-09-01 04:00:00', 'DEMO-S2-0',    'REQ-DEMO-S2-0',    TRUE, NULL, TIMESTAMP '2026-09-01 04:05:00'),
    ('V-DEMO', 'S2', 50,  34.860, TIMESTAMP '2026-09-01 04:30:00', 'DEMO-S2-50',   'REQ-DEMO-S2-50',   TRUE, NULL, TIMESTAMP '2026-09-01 04:35:00'),
    ('V-DEMO', 'S2', 100, 34.110, TIMESTAMP '2026-09-01 05:00:00', 'DEMO-S2-100',  'REQ-DEMO-S2-100',  TRUE, NULL, TIMESTAMP '2026-09-01 05:05:00');
