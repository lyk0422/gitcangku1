-- 合成演示数据：曲线证书 INS-DEMO，节点 (0,0)、(3,1)、(6,4)，有效期 2020～2030（UTC 左闭右开）。
-- 幂等写入：重复初始化（同一 JVM 内多上下文）不会产生重复数据。
INSERT INTO calibration_certificate
    (instrument_id, cert_type, valid_from, valid_to, coeff_a, offset_b, revoked, created_at)
SELECT 'INS-DEMO', 'CURVE', '2020-01-01 00:00:00', '2030-01-01 00:00:00', NULL, NULL, FALSE,
       '2020-01-01 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM calibration_certificate WHERE instrument_id = 'INS-DEMO');

INSERT INTO certificate_node (certificate_id, node_index, raw_value, corrected_value)
SELECT c.id, t.node_index, t.raw_value, t.corrected_value
FROM calibration_certificate c
JOIN (SELECT 0 AS node_index, 0 AS raw_value, 0 AS corrected_value
      UNION ALL SELECT 1, 3, 1
      UNION ALL SELECT 2, 6, 4) t
WHERE c.instrument_id = 'INS-DEMO'
  AND NOT EXISTS (SELECT 1 FROM certificate_node n WHERE n.certificate_id = c.id);
