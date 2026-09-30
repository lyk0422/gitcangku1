-- 合成种子批次初始化数据，供本地启动后直接查询检测链；测试场景使用各自独立的批次编号并自行清理。
INSERT INTO seed_batch (batch_no, crop_name, created_at)
VALUES ('SYN-B-001', '合成水稻', '2026-09-01 09:00:00');
