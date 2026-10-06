-- 本地启动合成数据（幂等）：一台合成仪器与一张迟滞校准证书（斜率 1、上升偏移 1、下降偏移 -1）。
-- 仅用于本地 H2 内存库演示；测试在每次用例前清理全部业务表，不依赖本数据。

INSERT INTO instrument_lock (instrument_id, created_at)
SELECT 'INS-DEMO', '2020-01-01 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM instrument_lock WHERE instrument_id = 'INS-DEMO');

INSERT INTO calibration_certificate
    (instrument_id, valid_from, valid_to, cert_type, slope, up_offset, down_offset, revoked, created_at)
SELECT 'INS-DEMO', '2020-01-01 00:00:00', '2030-01-01 00:00:00', 'HYSTERESIS', 1, 1, -1, FALSE, '2020-01-01 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM calibration_certificate WHERE instrument_id = 'INS-DEMO');
