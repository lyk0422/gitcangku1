-- 合成种子数据（仅本地 H2 内存库，JVM 生命周期内有效；所有时间均为 UTC 墙钟字面量）。
-- 覆盖：有效证书、仪器阈值 v1、待放行测量（无预算）、带两分量预算的待放行测量。

INSERT INTO calibration_certificate
    (instrument_id, valid_from, valid_to, coeff_a, offset_b, revoked, created_at)
VALUES
    ('INS-DEMO', '2026-01-01 00:00:00', '2028-01-01 00:00:00', 1, 0, FALSE, '2026-01-01 00:00:00');

INSERT INTO uncertainty_threshold
    (instrument_id, version, max_uncertainty, created_by, created_at)
VALUES
    ('INS-DEMO', 1, 5, 'seed-admin', '2026-01-02 00:00:00');

-- 待放行测量 1：无不确定度预算（current_budget_id 为 NULL，空预算合成值视为 0）
INSERT INTO measurement
    (measurement_key, instrument_id, measured_at, raw_reading, lower_limit, upper_limit,
     submitted_by, certificate_id, computed_value, passed, status, version, current_budget_id, created_at)
VALUES
    ('M-DEMO-1', 'INS-DEMO', '2026-06-01 00:00:00', 1, 0, 9, 'alice',
     (SELECT id FROM calibration_certificate WHERE instrument_id = 'INS-DEMO' ORDER BY id LIMIT 1),
     1, TRUE, 'PENDING', 1, NULL, '2026-06-01 00:00:00');

-- 待放行测量 2：带预算（先建测量，预算版本随后插入并回填 current_budget_id）
INSERT INTO measurement
    (measurement_key, instrument_id, measured_at, raw_reading, lower_limit, upper_limit,
     submitted_by, certificate_id, computed_value, passed, status, version, current_budget_id, created_at)
VALUES
    ('M-DEMO-2', 'INS-DEMO', '2026-06-02 00:00:00', 2, 0, 9, 'alice',
     (SELECT id FROM calibration_certificate WHERE instrument_id = 'INS-DEMO' ORDER BY id LIMIT 1),
     2, TRUE, 'PENDING', 1, NULL, '2026-06-02 00:00:00');

-- 预算 v1：分量 [3,4]，RSS 合成 = 5.000000000000，固化阈值版本 1（上限 5，含端点满足）
INSERT INTO uncertainty_budget
    (measurement_id, budget_version, calc_method, combined_uncertainty,
     threshold_id, threshold_version, threshold_value, created_by, created_at)
SELECT m.id, 1, 'RSS_V1', 5.000000000000, t.id, 1, 5, 'seed-budget', '2026-06-02 00:01:00'
FROM measurement m
JOIN uncertainty_threshold t
  ON t.instrument_id = 'INS-DEMO' AND t.version = 1
WHERE m.measurement_key = 'M-DEMO-2';

INSERT INTO uncertainty_component
    (budget_id, component_name, component_value, ordinal, created_at)
SELECT b.id, 'repeatability', 3, 0, '2026-06-02 00:01:00'
FROM uncertainty_budget b
JOIN measurement m ON m.id = b.measurement_id
WHERE m.measurement_key = 'M-DEMO-2' AND b.budget_version = 1;

INSERT INTO uncertainty_component
    (budget_id, component_name, component_value, ordinal, created_at)
SELECT b.id, 'reference', 4, 1, '2026-06-02 00:01:00'
FROM uncertainty_budget b
JOIN measurement m ON m.id = b.measurement_id
WHERE m.measurement_key = 'M-DEMO-2' AND b.budget_version = 1;

UPDATE measurement
SET current_budget_id = (
        SELECT b.id FROM uncertainty_budget b
        JOIN measurement mm ON mm.id = b.measurement_id
        WHERE mm.measurement_key = 'M-DEMO-2' AND b.budget_version = 1),
    version = 2
WHERE measurement_key = 'M-DEMO-2';
