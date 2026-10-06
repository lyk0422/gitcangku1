-- 本地启动的合成演示数据：两条二维坐标观测，协方差矩阵均为单位阵，
-- 坐标差 (4, 0)，满足一致性确认条件（C*u^2 - 2*B*u*v + A*v^2 = 32 <= 36 = 9*(A*C - B^2)）。
-- 采用 INSERT ... SELECT ... WHERE NOT EXISTS 幂等写入（H2 与 MySQL 均兼容），
-- 多个应用上下文共享同名内存库时重复执行不会主键冲突。
-- 仅用于本地 H2 内存库演示；测试在每个用例前清理场景数据，不依赖本数据。

INSERT INTO geo_observation_current (observation_id, x, y, a, b, c, version, deleted)
SELECT 'geo-demo-1', '0', '0', '1', '0', '1', 1, FALSE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM geo_observation_current WHERE observation_id = 'geo-demo-1');
INSERT INTO geo_observation_current (observation_id, x, y, a, b, c, version, deleted)
SELECT 'geo-demo-2', '4', '0', '1', '0', '1', 1, FALSE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM geo_observation_current WHERE observation_id = 'geo-demo-2');

INSERT INTO geo_observation_version (observation_id, version, x, y, a, b, c, deleted)
SELECT 'geo-demo-1', 1, '0', '0', '1', '0', '1', FALSE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM geo_observation_version WHERE observation_id = 'geo-demo-1' AND version = 1);
INSERT INTO geo_observation_version (observation_id, version, x, y, a, b, c, deleted)
SELECT 'geo-demo-2', 1, '4', '0', '1', '0', '1', FALSE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM geo_observation_version WHERE observation_id = 'geo-demo-2' AND version = 1);
