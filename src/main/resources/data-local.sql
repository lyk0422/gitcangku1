-- 本地合成数据（仅 local profile，H2 内存库；每次启动全新初始化，进程退出即丢失）。
-- 仪器 INS-ENV：证书允许温度 20～25℃（含端点）、湿度 40～60%RH；
-- 系数版本 v1：参考温度 20℃、参考湿度 50%RH，温度系数 0.01、湿度系数 0.001，2026 全年开放。
INSERT INTO calibration_certificate
    (id, instrument_id, valid_from, valid_to, coeff_a, offset_b,
     temp_min, temp_max, humidity_min, humidity_max, revoked, created_at)
VALUES
    (1, 'INS-ENV', '2026-01-01 00:00:00', '2028-01-01 00:00:00', 1, 0,
     20, 25, 40, 60, FALSE, '2026-01-01 00:00:00');

INSERT INTO coefficient_version
    (id, instrument_id, effective_from, effective_to, ref_temperature, ref_humidity,
     temperature_coeff, humidity_coeff, created_at)
VALUES
    (1, 'INS-ENV', '2026-01-01 00:00:00', NULL, 20, 50, 0.01, 0.001, '2026-01-01 00:00:00');

-- 待放行测量：读数 10，计算值 10（a=1,b=0），合格区间 0～20，提交人 alice。
INSERT INTO measurement
    (id, measurement_key, instrument_id, measured_at, raw_reading, lower_limit, upper_limit,
     submitted_by, certificate_id, computed_value, passed, status, created_at)
VALUES
    (1, 'M-LOCAL', 'INS-ENV', '2026-06-01 00:00:00', 10, 0, 20,
     'alice', 1, 10, TRUE, 'PENDING', '2026-06-01 00:00:00');

-- 显式插入主键后推进 H2 自增序列，保证后续经 API 新建的数据从 2 开始而不与种子冲突。
ALTER TABLE calibration_certificate ALTER COLUMN id RESTART WITH 2;
ALTER TABLE coefficient_version ALTER COLUMN id RESTART WITH 2;
ALTER TABLE measurement ALTER COLUMN id RESTART WITH 2;

