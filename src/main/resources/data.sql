-- 本地入口合成数据（H2 内存库，随 JVM 退出而丢失）。
-- 使用 MERGE INTO 保证重复初始化幂等；合成行使用负数 ID，避免与自增序列冲突。
-- 时间均为 UTC 墙钟时间。

-- 合成证书：INS-DEMO 仪器，有效期 [2020-01-01, 2030-01-01)，系数 a=1，偏移 b=0。
MERGE INTO calibration_certificate KEY(id) VALUES (
    -1, 'INS-DEMO', '2020-01-01 00:00:00', '2030-01-01 00:00:00',
    1.000000, 0.000000, 1, FALSE, NULL, '2026-01-01 00:00:00');

-- 合成批次：目标值 10，允许偏差 0.5。
MERGE INTO measurement_batch KEY(id) VALUES (
    -1, 'BATCH-DEMO', 'INS-DEMO', 10.000000, 0.500000, 1, 'OPEN',
    NULL, NULL, '2026-01-01 00:00:00');

-- 合成读数：10.1 在偏差内；10.9 超出允许偏差（实际偏差 0.9 > 0.5）为离群。
MERGE INTO batch_reading KEY(id) VALUES (
    -1, 'READING-DEMO-1', -1, '2026-06-01 00:00:00', 10.100000, 'demo-submitter',
    -1, 1, 1, FALSE, '2026-06-01 00:00:01');
MERGE INTO batch_reading KEY(id) VALUES (
    -2, 'READING-DEMO-2', -1, '2026-06-01 00:00:00', 10.900000, 'demo-submitter',
    -1, 1, 1, FALSE, '2026-06-01 00:00:02');

-- 合成离群记录：封存目标值 10、允许偏差 0.5、读数 10.9、实际偏差 0.9。
MERGE INTO outlier_record KEY(id) VALUES (
    -1, -1, -2, 1, 10.000000, 0.500000, 10.900000, 0.900000000000,
    'PENDING', '2026-06-01 00:00:02', NULL);

-- 合成统计记录：两条读数，均值 10.5。
MERGE INTO batch_stats_record KEY(id) VALUES (
    -1, -1, 1, 'SUBMIT', 2, 0, 10.500000000000, 10.100000, 10.900000,
    '2026-06-01 00:00:02');