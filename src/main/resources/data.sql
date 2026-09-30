-- 合成种子批次数据（H2 MERGE，重启重复初始化不报错）
MERGE INTO seed_batch (batch_no, crop_name, created_at) KEY (batch_no)
VALUES ('BATCH-2026-1001', '合成水稻-南粳46', TIMESTAMP '2026-09-01 02:00:00');
MERGE INTO seed_batch (batch_no, crop_name, created_at) KEY (batch_no)
VALUES ('BATCH-2026-1002', '合成玉米-郑单958', TIMESTAMP '2026-09-02 02:00:00');
MERGE INTO seed_batch (batch_no, crop_name, created_at) KEY (batch_no)
VALUES ('BATCH-2026-1003', '合成小麦-扬麦25', TIMESTAMP '2026-09-03 02:00:00');
