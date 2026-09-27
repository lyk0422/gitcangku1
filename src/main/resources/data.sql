-- 本地入口合成数据：随 H2 内存库启动初始化，便于一条调用走通主流程。
-- 全部写入幂等（NOT EXISTS 守卫），重复执行或部署到外部 MySQL 时不产生重复行。
-- 时间均为 UTC 墙钟时间；区间左闭右开。

-- 1) 演示仪器校准证书：INS-SEED，[2026-01-01, 2028-01-01)，a=1，b=0
INSERT INTO calibration_certificate
    (instrument_id, valid_from, valid_to, coeff_a, offset_b, revoked, created_at)
SELECT 'INS-SEED', '2026-01-01 00:00:00.000000', '2028-01-01 00:00:00.000000', 1, 0, FALSE, '2026-01-01 00:00:00.000000'
WHERE NOT EXISTS (
    SELECT 1 FROM calibration_certificate
    WHERE instrument_id = 'INS-SEED' AND valid_from = '2026-01-01 00:00:00.000000');

-- 2) 演示仪器不确定度阈值版本 v1：开放区间 [2026-01-01, ∞)，上限 1.000000
INSERT INTO instrument_threshold_version
    (instrument_id, version, valid_from, valid_to, max_uncertainty, created_at)
SELECT 'INS-SEED', 1, '2026-01-01 00:00:00.000000', NULL, 1, '2026-01-01 00:00:00.000000'
WHERE NOT EXISTS (
    SELECT 1 FROM instrument_threshold_version
    WHERE instrument_id = 'INS-SEED' AND version = 1);

-- 3) 演示测量：读数 1，区间 [0, 9]，提交人 alice，待放行；计算值 a×1+b=1，合格
INSERT INTO measurement
    (measurement_key, instrument_id, measured_at, raw_reading, lower_limit, upper_limit,
     submitted_by, certificate_id, computed_value, passed, status, version, created_at)
SELECT 'M-SEED', 'INS-SEED', '2026-06-01 00:00:00.000000', 1, 0, 9, 'alice',
       (SELECT id FROM calibration_certificate
        WHERE instrument_id = 'INS-SEED' AND valid_from = '2026-01-01 00:00:00.000000'),
       1, TRUE, 'PENDING', 1, '2026-06-01 00:00:00.000000'
WHERE NOT EXISTS (SELECT 1 FROM measurement WHERE measurement_key = 'M-SEED');

-- 4) 演示不确定度预算（预算版本 1）：u_rep=0.3、u_ref=0.4，合成 sqrt(0.09+0.16)=0.5000 ≤ 阈值 1
INSERT INTO uncertainty_component
    (measurement_id, budget_version, component_name, magnitude, created_at)
SELECT m.id, 1, 'u_ref', 0.4, '2026-06-01 00:00:00.000000'
FROM measurement m
WHERE m.measurement_key = 'M-SEED'
  AND NOT EXISTS (SELECT 1 FROM uncertainty_component uc
                  WHERE uc.measurement_id = m.id AND uc.budget_version = 1 AND uc.component_name = 'u_ref');

INSERT INTO uncertainty_component
    (measurement_id, budget_version, component_name, magnitude, created_at)
SELECT m.id, 1, 'u_rep', 0.3, '2026-06-01 00:00:00.000000'
FROM measurement m
WHERE m.measurement_key = 'M-SEED'
  AND NOT EXISTS (SELECT 1 FROM uncertainty_component uc
                  WHERE uc.measurement_id = m.id AND uc.budget_version = 1 AND uc.component_name = 'u_rep');
