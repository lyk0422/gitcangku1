-- 本地入口合成数据：一张演示证书与一个演示测量批次，启动后即可直接调用。
-- 仅在本地入口（H2 内存库）加载；测试环境通过 data-locations 覆盖跳过本文件。
INSERT INTO calibration_certificate (instrument_id, valid_from, valid_to, coeff_a, offset_b, version, revoked, created_at)
VALUES ('INS-DEMO', '2020-01-01 00:00:00', '2030-01-01 00:00:00', 1.0, 0.0, 1, FALSE, '2026-01-01 00:00:00');

INSERT INTO calibration_batch (batch_key, instrument_id, target_value, allowed_deviation, revision, status, created_at)
VALUES ('BATCH-DEMO', 'INS-DEMO', 10.0, 0.5, 1, 'OPEN', '2026-01-01 00:00:00');