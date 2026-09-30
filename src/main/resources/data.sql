-- 演示用合成数据：随 H2 内存库启动时初始化，仅用于本地可复现调用；内存数据随 JVM 退出丢失，不承诺跨重启恢复。
-- 来源甲：配额 10 立方米，水质指标 2；来源乙：配额 10 立方米，水质指标 4。阈值 3 时各取 10 立方米加权恰为 3。
INSERT INTO blend_source (source_key, name, quota_volume, quality_index, created_nanos)
VALUES ('demo-jia', '来源甲', 10.000, 2.000000, 1780000000000000000);
INSERT INTO blend_source (source_key, name, quota_volume, quality_index, created_nanos)
VALUES ('demo-yi', '来源乙', 10.000, 4.000000, 1780000000000000000);
