-- 合成站点主数据（H2 MERGE，重复执行幂等）。
-- 站点 A：天线 ANT-A，仰角门槛 10 度，容量 60000 字节/分钟（10 分钟可容纳 600000 字节）。
MERGE INTO station (id, code, name, antenna_code, min_elevation_deg, bytes_per_minute, created_at, updated_at)
KEY (id)
VALUES (1, 'A', '站点A', 'ANT-A', 10.00, 60000, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00');

-- 站点 B：天线 ANT-B，仰角门槛 20 度（更高），容量 30000 字节/分钟。
MERGE INTO station (id, code, name, antenna_code, min_elevation_deg, bytes_per_minute, created_at, updated_at)
KEY (id)
VALUES (2, 'B', '站点B', 'ANT-B', 20.00, 30000, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00');
