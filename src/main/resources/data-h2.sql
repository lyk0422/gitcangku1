-- 本地演示合成数据（仅本地入口加载，测试库不加载）：演示频道 demo、保底素材与两个 20 秒合成素材 A/B，
-- 可直接用于交叉淡化混音演示：A/B 各 20 秒、淡化 4 秒时 B 在 A 开始后第 16 秒切入，总时长 36 秒。
-- 时间列均为 UTC 纪元毫秒，业务日为 Asia/Shanghai 日历日。

INSERT INTO playout_asset (id, duration_ms, created_at_ms) VALUES
    ('demo-fallback', 30000, 0),
    ('demo-a', 20000, 0),
    ('demo-b', 20000, 0);

INSERT INTO playout_channel (id, fallback_asset_id, created_at_ms) VALUES
    ('demo', 'demo-fallback', 0);
