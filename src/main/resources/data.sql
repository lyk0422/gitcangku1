-- 本题合成数据：证物 EV-SYN-A/B/C 均由 syn-custodian 保管且 SEALED；
-- 已有接触批次 CB-SYN-1{A,B}（事件序号 1）、CB-SYN-2{B,C}（事件序号 2）。
-- 从 A 在第一批污染将隔离 A、B、C；从 B 在第二批污染只隔离 B、C，不逆向追溯 A。
-- 全部语句幂等：同一 JVM 内重复初始化不会报错或重复写入。

INSERT INTO contact_event_seq (id, next_seq)
SELECT 1, 3 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM contact_event_seq WHERE id = 1);

INSERT INTO evidence (evidence_key, case_key, category, seal_no, custodian_id, status, created_at, updated_at)
SELECT 'EV-SYN-A', 'CASE-SYN', 'DOCUMENT', 'SEAL-SYN-A', 'syn-custodian', 'SEALED', '2026-01-01 00:00:00', '2026-01-01 00:00:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM evidence WHERE evidence_key = 'EV-SYN-A');

INSERT INTO evidence (evidence_key, case_key, category, seal_no, custodian_id, status, created_at, updated_at)
SELECT 'EV-SYN-B', 'CASE-SYN', 'DOCUMENT', 'SEAL-SYN-B', 'syn-custodian', 'SEALED', '2026-01-01 00:00:00', '2026-01-01 00:00:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM evidence WHERE evidence_key = 'EV-SYN-B');

INSERT INTO evidence (evidence_key, case_key, category, seal_no, custodian_id, status, created_at, updated_at)
SELECT 'EV-SYN-C', 'CASE-SYN', 'DOCUMENT', 'SEAL-SYN-C', 'syn-custodian', 'SEALED', '2026-01-01 00:00:00', '2026-01-01 00:00:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM evidence WHERE evidence_key = 'EV-SYN-C');

INSERT INTO contact_batch (batch_key, event_seq, custodian_id, member_count, created_at)
SELECT 'CB-SYN-1', 1, 'syn-custodian', 2, '2026-01-01 01:00:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM contact_batch WHERE batch_key = 'CB-SYN-1');

INSERT INTO contact_batch (batch_key, event_seq, custodian_id, member_count, created_at)
SELECT 'CB-SYN-2', 2, 'syn-custodian', 2, '2026-01-01 02:00:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM contact_batch WHERE batch_key = 'CB-SYN-2');

INSERT INTO contact_batch_member (batch_key, evidence_key, created_at)
SELECT 'CB-SYN-1', 'EV-SYN-A', '2026-01-01 01:00:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM contact_batch_member WHERE batch_key = 'CB-SYN-1' AND evidence_key = 'EV-SYN-A');

INSERT INTO contact_batch_member (batch_key, evidence_key, created_at)
SELECT 'CB-SYN-1', 'EV-SYN-B', '2026-01-01 01:00:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM contact_batch_member WHERE batch_key = 'CB-SYN-1' AND evidence_key = 'EV-SYN-B');

INSERT INTO contact_batch_member (batch_key, evidence_key, created_at)
SELECT 'CB-SYN-2', 'EV-SYN-B', '2026-01-01 02:00:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM contact_batch_member WHERE batch_key = 'CB-SYN-2' AND evidence_key = 'EV-SYN-B');

INSERT INTO contact_batch_member (batch_key, evidence_key, created_at)
SELECT 'CB-SYN-2', 'EV-SYN-C', '2026-01-01 02:00:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM contact_batch_member WHERE batch_key = 'CB-SYN-2' AND evidence_key = 'EV-SYN-C');
