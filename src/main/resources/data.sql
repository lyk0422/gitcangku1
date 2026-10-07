-- 合成演示数据（幂等写入）：仪器 INS-DEMO，证书 a=1、b=0，
-- 漂移档案 2026-06-01 10:00 偏移 0、11:00 偏移 2。
-- 业务调用示例：POST /api/measurements/drift，measuredAt=2026-06-01T10:30:00Z、reading=5
-- → 线性插值偏移 1，校正值 5-1=4（computedValue "4"，displayValue "4.0000"）。
INSERT INTO calibration_certificate (instrument_id, valid_from, valid_to, coeff_a, offset_b, revoked, created_at)
SELECT 'INS-DEMO', '2026-01-01 00:00:00', '2028-01-01 00:00:00', 1, 0, FALSE, '2026-01-01 00:00:00' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM calibration_certificate WHERE instrument_id = 'INS-DEMO');

INSERT INTO drift_profile (instrument_id, profiled_at, reference_offset, revoked, created_at)
SELECT 'INS-DEMO', '2026-06-01 10:00:00', 0, FALSE, '2026-01-01 00:00:00' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM drift_profile WHERE instrument_id = 'INS-DEMO'
    AND profiled_at = '2026-06-01 10:00:00');

INSERT INTO drift_profile (instrument_id, profiled_at, reference_offset, revoked, created_at)
SELECT 'INS-DEMO', '2026-06-01 11:00:00', 2, FALSE, '2026-01-01 00:00:00' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM drift_profile WHERE instrument_id = 'INS-DEMO'
    AND profiled_at = '2026-06-01 11:00:00');
