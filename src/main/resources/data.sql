-- 合成演示数据：三个站点，供本地启动直接调用
INSERT INTO station (station_code, station_name)
SELECT 'ST-ALPHA', 'Alpha 野外站' WHERE NOT EXISTS (SELECT 1 FROM station WHERE station_code = 'ST-ALPHA');
INSERT INTO station (station_code, station_name)
SELECT 'ST-BRAVO', 'Bravo 野外站' WHERE NOT EXISTS (SELECT 1 FROM station WHERE station_code = 'ST-BRAVO');
INSERT INTO station (station_code, station_name)
SELECT 'ST-CHARLIE', 'Charlie 野外站' WHERE NOT EXISTS (SELECT 1 FROM station WHERE station_code = 'ST-CHARLIE');