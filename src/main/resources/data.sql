-- 本地启动与测试共用的合成初始数据（H2 内存库每次启动重建；语句幂等，重复执行不报错）。
-- 两个观测点均使用单位协方差矩阵（a=c=1, b=0，单位平方米），坐标单位米：
-- demo-point-a 位于 (0,0)，demo-point-b 位于 (4,0)，坐标差 (4,0)。
-- 合成验证：矩阵和 A=C=2、B=0，左侧 2*4^2=32，右侧 9*(2*2-0)=36，32<=36，一致性确认可通过；
-- 若把 demo-point-b 移到 (4.243,0)，左侧 2*4.243^2=36.006098>36，应被拒绝（422）。

INSERT INTO positioned_observation_current (observation_id, x, y, a, b, c, version, deleted, updated_at)
SELECT 'demo-point-a', 0.000, 0.000, 1.000, 0.000, 1.000, 1, FALSE, CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM positioned_observation_current WHERE observation_id = 'demo-point-a');

INSERT INTO positioned_observation_current (observation_id, x, y, a, b, c, version, deleted, updated_at)
SELECT 'demo-point-b', 4.000, 0.000, 1.000, 0.000, 1.000, 1, FALSE, CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM positioned_observation_current WHERE observation_id = 'demo-point-b');

INSERT INTO positioned_observation_version (observation_id, version, x, y, a, b, c, deleted, created_at)
SELECT 'demo-point-a', 1, 0.000, 0.000, 1.000, 0.000, 1.000, FALSE, CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM positioned_observation_version
                  WHERE observation_id = 'demo-point-a' AND version = 1);

INSERT INTO positioned_observation_version (observation_id, version, x, y, a, b, c, deleted, created_at)
SELECT 'demo-point-b', 1, 4.000, 0.000, 1.000, 0.000, 1.000, FALSE, CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM positioned_observation_version
                  WHERE observation_id = 'demo-point-b' AND version = 1);
