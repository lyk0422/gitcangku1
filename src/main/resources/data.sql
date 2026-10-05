-- 合成演示数据：本地入口启动时自动初始化；全部幂等（已存在则跳过）。
-- 合成曲线证书 INS-DEMO：节点 (0,0)、(3,1)、(6,4)，有效期 2026-01-01（含）～2028-01-01（不含）UTC。
INSERT INTO instrument_lock (instrument_id, created_at)
SELECT 'INS-DEMO', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM instrument_lock WHERE instrument_id = 'INS-DEMO');

INSERT INTO calibration_certificate
    (instrument_id, valid_from, valid_to, cert_type, coeff_a, offset_b, revoked, created_at)
SELECT 'INS-DEMO', '2026-01-01 00:00:00', '2028-01-01 00:00:00', 'CURVE', NULL, NULL, FALSE, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM calibration_certificate WHERE instrument_id = 'INS-DEMO');

INSERT INTO certificate_node (certificate_id, seq, raw_value, corrected_value)
SELECT c.id, 0, 0, 0 FROM calibration_certificate c
WHERE c.instrument_id = 'INS-DEMO'
  AND NOT EXISTS (SELECT 1 FROM certificate_node n WHERE n.certificate_id = c.id AND n.seq = 0);

INSERT INTO certificate_node (certificate_id, seq, raw_value, corrected_value)
SELECT c.id, 1, 3, 1 FROM calibration_certificate c
WHERE c.instrument_id = 'INS-DEMO'
  AND NOT EXISTS (SELECT 1 FROM certificate_node n WHERE n.certificate_id = c.id AND n.seq = 1);

INSERT INTO certificate_node (certificate_id, seq, raw_value, corrected_value)
SELECT c.id, 2, 6, 4 FROM calibration_certificate c
WHERE c.instrument_id = 'INS-DEMO'
  AND NOT EXISTS (SELECT 1 FROM certificate_node n WHERE n.certificate_id = c.id AND n.seq = 2);
