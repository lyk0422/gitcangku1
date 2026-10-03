-- 合成示例程序：STD-FIRING v1，覆盖 2026-01-01T00:00:00Z 起 3 小时，三段连续
INSERT INTO kiln_program (id, name, version, created_at)
SELECT 1, 'STD-FIRING', 'v1', TIMESTAMP '2025-12-31 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM kiln_program WHERE id = 1);

INSERT INTO kiln_segment (program_id, seq, start_utc, end_utc, target_temp, tolerance)
SELECT 1, 1, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 01:00:00', 800.00, 20.00
WHERE NOT EXISTS (SELECT 1 FROM kiln_segment WHERE program_id = 1 AND seq = 1);
INSERT INTO kiln_segment (program_id, seq, start_utc, end_utc, target_temp, tolerance)
SELECT 1, 2, TIMESTAMP '2026-01-01 01:00:00', TIMESTAMP '2026-01-01 02:00:00', 1100.00, 25.00
WHERE NOT EXISTS (SELECT 1 FROM kiln_segment WHERE program_id = 1 AND seq = 2);
INSERT INTO kiln_segment (program_id, seq, start_utc, end_utc, target_temp, tolerance)
SELECT 1, 3, TIMESTAMP '2026-01-01 02:00:00', TIMESTAMP '2026-01-01 03:00:00', 950.00, 15.00
WHERE NOT EXISTS (SELECT 1 FROM kiln_segment WHERE program_id = 1 AND seq = 3);