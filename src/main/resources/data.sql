-- 本地运行用合成数据：一个可用花粉批次、一个已停用花粉批次（UTC 时间）。
INSERT INTO pollen_batch (batch_no, status, created_at)
SELECT 'PB-2026-ACTIVE', 'ACTIVE', TIMESTAMP '2026-01-01 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM pollen_batch WHERE batch_no = 'PB-2026-ACTIVE');

INSERT INTO pollen_batch (batch_no, status, created_at)
SELECT 'PB-2026-DISABLED', 'DISABLED', TIMESTAMP '2026-01-01 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM pollen_batch WHERE batch_no = 'PB-2026-DISABLED');