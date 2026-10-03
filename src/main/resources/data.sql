-- 合成演示数据：两根天线、一条当前时刻通过的校准（有效期24小时）、一条落在有效期内的任务。
-- 时间基于启动时刻生成，仅用于本地启动后的联调演示，不作为业务验证依据。
-- 语句幂等：同一命名库被多个应用上下文初始化时不产生唯一键冲突。

INSERT INTO antenna (antenna_code)
SELECT 'ANT-01' WHERE NOT EXISTS (SELECT 1 FROM antenna WHERE antenna_code = 'ANT-01');
INSERT INTO antenna (antenna_code)
SELECT 'ANT-02' WHERE NOT EXISTS (SELECT 1 FROM antenna WHERE antenna_code = 'ANT-02');

INSERT INTO antenna_calibration (
    antenna_id, calibrated_at, azimuth_deviation_deg, elevation_deviation_deg,
    passed, valid_from, valid_until, status, created_at
)
SELECT a.id, CURRENT_TIMESTAMP, 0.1000, -0.0800,
       TRUE, CURRENT_TIMESTAMP, DATEADD('HOUR', 24, CURRENT_TIMESTAMP), 'ACTIVE', CURRENT_TIMESTAMP
FROM antenna a
WHERE a.antenna_code = 'ANT-01'
  AND NOT EXISTS (SELECT 1 FROM antenna_calibration c WHERE c.antenna_id = a.id);

INSERT INTO receive_task (task_code, antenna_id, start_time, end_time, status, created_at)
SELECT 'DEMO-TASK-01', a.id,
       DATEADD('HOUR', 1, CURRENT_TIMESTAMP), DATEADD('HOUR', 3, CURRENT_TIMESTAMP),
       'SCHEDULED', CURRENT_TIMESTAMP
FROM antenna a
WHERE a.antenna_code = 'ANT-01'
  AND NOT EXISTS (SELECT 1 FROM receive_task t WHERE t.task_code = 'DEMO-TASK-01');