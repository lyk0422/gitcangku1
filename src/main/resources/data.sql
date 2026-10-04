-- 合成演示数据：demo-ridge-01 处于封闭状态，最近三个整点新增积雪 4、5、6 厘米，
-- 最后一小时超限，用于演示解除审核被 422 拒绝（看逐小时读数而非平均值）。
INSERT INTO zone_state (zone_id, status, reading_version, updated_at)
SELECT 'demo-ridge-01', 'CLOSED', 3, TIMESTAMP '2026-10-03 11:05:00'
WHERE NOT EXISTS (SELECT 1 FROM zone_state WHERE zone_id = 'demo-ridge-01');

INSERT INTO closure_command (zone_id, request_id, source, reason, status, created_at, lifted_at)
SELECT 'demo-ridge-01', NULL, 'AUTO', 'new snow 12cm over limit', 'ACTIVE', TIMESTAMP '2026-10-03 07:05:00', NULL
WHERE NOT EXISTS (SELECT 1 FROM closure_command WHERE zone_id = 'demo-ridge-01');

INSERT INTO snow_reading (zone_id, hour_utc, snow_depth_cm, new_snow_cm, request_id, created_at)
SELECT * FROM (
    SELECT 'demo-ridge-01', TIMESTAMP '2026-10-03 08:00:00', 118, 4, 'seed-r1', TIMESTAMP '2026-10-03 08:05:00'
    UNION ALL
    SELECT 'demo-ridge-01', TIMESTAMP '2026-10-03 09:00:00', 123, 5, 'seed-r2', TIMESTAMP '2026-10-03 09:05:00'
    UNION ALL
    SELECT 'demo-ridge-01', TIMESTAMP '2026-10-03 10:00:00', 129, 6, 'seed-r3', TIMESTAMP '2026-10-03 10:05:00'
) seed
WHERE NOT EXISTS (SELECT 1 FROM snow_reading WHERE zone_id = 'demo-ridge-01');