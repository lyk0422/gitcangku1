-- 合成演示数据：所有人物、地点均为虚构，仅用于本地入口启动后的联调。
INSERT INTO stratum (site_code, stratum_no, description, created_at) VALUES
('SYNTH-SITE-01', 'L1', '合成遗址一号 L1 层位（演示用合成数据）', TIMESTAMP '2026-01-10 08:00:00'),
('SYNTH-SITE-01', 'L2', '合成遗址一号 L2 层位（演示用合成数据）', TIMESTAMP '2026-01-11 08:00:00');

INSERT INTO sample (sample_no, stratum_id, grid_ref, collected_at, status, created_at) VALUES
('SYN-0001', 1, 'G-03-07', TIMESTAMP '2026-01-12 09:00:00', 'COLLECTED', TIMESTAMP '2026-01-12 09:00:00'),
('SYN-0002', 1, 'G-03-08', TIMESTAMP '2026-01-12 09:05:00', 'COLLECTED', TIMESTAMP '2026-01-12 09:05:00'),
('SYN-0003', 2, 'G-05-01', TIMESTAMP '2026-01-13 10:00:00', 'COLLECTED', TIMESTAMP '2026-01-13 10:00:00');