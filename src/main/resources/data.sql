-- 合成演示数据：仪器 INS-SYN，证书 a=1、b=0（校正值=读数）。
-- 参考件读数 9.8、待测件读数 10.1，采样时刻相差 5 秒，差值 0.3 落在闭区间 [0, 0.3] 内，
-- 可直接用于差分创建与放行演示。显式使用大号 ID，避免与自增序列冲突。
INSERT INTO calibration_certificate (id, instrument_id, valid_from, valid_to, coeff_a, offset_b, revoked, created_at)
SELECT 900001, 'INS-SYN', '2026-01-01 00:00:00', '2028-01-01 00:00:00', 1, 0, FALSE, '2026-01-01 00:00:00' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM calibration_certificate WHERE id = 900001);

INSERT INTO measurement (id, measurement_key, instrument_id, measured_at, raw_reading, lower_limit, upper_limit,
                         submitted_by, certificate_id, computed_value, passed, status, created_at)
SELECT 900001, 'SYN-REF', 'INS-SYN', '2026-06-01 00:00:00', 9.8, 0, 100,
       'syn-reference', 900001, 9.8, TRUE, 'PENDING', '2026-06-01 00:00:00' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM measurement WHERE measurement_key = 'SYN-REF');

INSERT INTO measurement (id, measurement_key, instrument_id, measured_at, raw_reading, lower_limit, upper_limit,
                         submitted_by, certificate_id, computed_value, passed, status, created_at)
SELECT 900002, 'SYN-DUT', 'INS-SYN', '2026-06-01 00:00:05', 10.1, 0, 100,
       'syn-dut', 900001, 10.1, TRUE, 'PENDING', '2026-06-01 00:00:05' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM measurement WHERE measurement_key = 'SYN-DUT');
