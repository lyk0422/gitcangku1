-- 合成井况数据：GW-001 注入段运行中，最近 15 分钟内 4 个采样，其中第 3 个为 12.1 MPa 离群读数，
-- 因此对该井直接申请切换会被拒绝（读数超出 8 至 12 MPa）。
INSERT INTO well_segment (well_no, mode, start_utc, end_utc, created_at)
VALUES ('GW-001', 'INJECTION', DATEADD('HOUR', -2, CURRENT_TIMESTAMP), NULL, DATEADD('HOUR', -2, CURRENT_TIMESTAMP));

INSERT INTO pressure_sample (well_no, request_id, payload_hash, pressure_mpa, sampled_at, voided, created_at)
VALUES
 ('GW-001', 'seed-sample-1', 'seed', 9.60, DATEADD('MINUTE', -12, CURRENT_TIMESTAMP), FALSE, DATEADD('MINUTE', -12, CURRENT_TIMESTAMP)),
 ('GW-001', 'seed-sample-2', 'seed', 10.20, DATEADD('MINUTE', -9, CURRENT_TIMESTAMP), FALSE, DATEADD('MINUTE', -9, CURRENT_TIMESTAMP)),
 ('GW-001', 'seed-sample-3', 'seed', 12.10, DATEADD('MINUTE', -6, CURRENT_TIMESTAMP), FALSE, DATEADD('MINUTE', -6, CURRENT_TIMESTAMP)),
 ('GW-001', 'seed-sample-4', 'seed', 10.40, DATEADD('MINUTE', -3, CURRENT_TIMESTAMP), FALSE, DATEADD('MINUTE', -3, CURRENT_TIMESTAMP));