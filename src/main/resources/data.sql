-- 本地启动合成演示数据：两条圆弧观测，交集跨零度（题干示例）。
-- 圆弧 arc-demo-1：起始角 350000（350.000 度），弧长 20000（20.000 度），跨零；
-- 圆弧 arc-demo-2：起始角 355000，弧长 10000；二者交集为起始 355000、弧长 10000 的跨零圆弧。
-- 幂等写入：已存在时跳过，保证共享命名内存库的多个应用上下文重复初始化不报错。

INSERT INTO arc_observation_current (observation_id, version, deleted, conflict_pending, updated_at)
SELECT 'arc-demo-1', 1, FALSE, FALSE, CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM arc_observation_current WHERE observation_id = 'arc-demo-1');
INSERT INTO arc_observation_version (observation_id, version, deleted, created_at)
SELECT 'arc-demo-1', 1, FALSE, CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM arc_observation_version WHERE observation_id = 'arc-demo-1' AND version = 1);
INSERT INTO arc_observation_arc (observation_id, version, arc_index, start_angle, arc_length, created_at)
SELECT 'arc-demo-1', 1, 0, 350000, 20000, CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM arc_observation_arc WHERE observation_id = 'arc-demo-1' AND version = 1 AND arc_index = 0);

INSERT INTO arc_observation_current (observation_id, version, deleted, conflict_pending, updated_at)
SELECT 'arc-demo-2', 1, FALSE, FALSE, CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM arc_observation_current WHERE observation_id = 'arc-demo-2');
INSERT INTO arc_observation_version (observation_id, version, deleted, created_at)
SELECT 'arc-demo-2', 1, FALSE, CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM arc_observation_version WHERE observation_id = 'arc-demo-2' AND version = 1);
INSERT INTO arc_observation_arc (observation_id, version, arc_index, start_angle, arc_length, created_at)
SELECT 'arc-demo-2', 1, 0, 355000, 10000, CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM arc_observation_arc WHERE observation_id = 'arc-demo-2' AND version = 1 AND arc_index = 0);
