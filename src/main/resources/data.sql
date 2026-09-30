-- 合成基础数据：泊位 B-01 静态水深 8.8 米；潮高版本 1 覆盖 2026-10-01 全天，
-- 08:00-12:00 最低潮高 0.7 米，区间首尾相接不留缺口。
MERGE INTO berth (id, code, name, static_depth_m) KEY (id)
VALUES (1, 'B-01', '一号泊位', 8.80);

MERGE INTO tide_version (id, version_no, published_at, remark) KEY (id)
VALUES (1, 1, TIMESTAMP '2026-09-30 12:00:00', '初始潮高版本，08:00-12:00 潮高 0.7 米');

MERGE INTO tide_window (id, version_id, start_time, end_time, tide_height_m) KEY (id) VALUES
(1, 1, TIMESTAMP '2026-10-01 00:00:00', TIMESTAMP '2026-10-01 08:00:00', 1.20),
(2, 1, TIMESTAMP '2026-10-01 08:00:00', TIMESTAMP '2026-10-01 10:00:00', 0.70),
(3, 1, TIMESTAMP '2026-10-01 10:00:00', TIMESTAMP '2026-10-01 12:00:00', 0.70),
(4, 1, TIMESTAMP '2026-10-01 12:00:00', TIMESTAMP '2026-10-01 18:00:00', 1.00),
(5, 1, TIMESTAMP '2026-10-01 18:00:00', TIMESTAMP '2026-10-02 00:00:00', 1.10);
