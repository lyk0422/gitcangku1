-- 合成初始数据：两个地面站，供本地入口直接调用演示。
-- 站点 A：S 频段，仰角门槛 5 度，容量 1,000,000 字节/分钟。
-- 站点 B：X 频段，仰角门槛 10 度，容量 4,000,000 字节/分钟。
INSERT INTO ground_station (code, name, antenna_capability, min_elevation_deg, capacity_bytes_per_min)
SELECT 'ST-A', '站点A', 'S-BAND', 5.00, 1000000
WHERE NOT EXISTS (SELECT 1 FROM ground_station WHERE code = 'ST-A');

INSERT INTO ground_station (code, name, antenna_capability, min_elevation_deg, capacity_bytes_per_min)
SELECT 'ST-B', '站点B', 'X-BAND', 10.00, 4000000
WHERE NOT EXISTS (SELECT 1 FROM ground_station WHERE code = 'ST-B');