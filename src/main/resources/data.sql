-- 本地启动用合成数据：一个100公斤红松来源及其根批次，测试启动后会清理。
INSERT INTO timber_source (id, voucher_no, species, weight_kg, holder, created_at)
VALUES (1, 'VOUCHER-2026-0001', '红松', 100, '加工点A', CURRENT_TIMESTAMP);
INSERT INTO timber_batch (id, batch_no, source_id, parent_id, voucher_no, species, weight_kg, holder, leaf, created_at)
VALUES (1, 'BATCH-ROOT-0001', 1, NULL, 'VOUCHER-2026-0001', '红松', 100, '加工点A', TRUE, CURRENT_TIMESTAMP);