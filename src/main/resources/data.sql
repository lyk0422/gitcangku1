-- 合成站点数据：A/B 两个接收站，供本地入口与演示调用
INSERT INTO ground_station (code, antenna_band, min_elevation_deg, capacity_mb_per_min)
SELECT 'A', 'S', 5.00, 100
WHERE NOT EXISTS (SELECT 1 FROM ground_station WHERE code = 'A');

INSERT INTO ground_station (code, antenna_band, min_elevation_deg, capacity_mb_per_min)
SELECT 'B', 'X', 10.00, 200
WHERE NOT EXISTS (SELECT 1 FROM ground_station WHERE code = 'B');