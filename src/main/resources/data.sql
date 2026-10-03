-- 合成演示数据：全部为虚构食品批次，仅用于本地启动后可直接查询
-- 批次1：已完成七日翻面与测温，尚未放行
INSERT INTO aging_batch (id, batch_code, entry_at, target_days, status, created_at)
VALUES (1, 'CHEESE-DEMO-001', '2026-09-20 08:00:00', 7, 'AGING', '2026-09-20 08:00:00');
INSERT INTO turn_record (batch_id, turn_date, request_id, operator_name, note, created_at) VALUES
 (1, '2026-09-20', 'demo-turn-001-1', 'operator-a', NULL, '2026-09-20 09:00:00'),
 (1, '2026-09-21', 'demo-turn-001-2', 'operator-a', NULL, '2026-09-21 09:00:00'),
 (1, '2026-09-22', 'demo-turn-001-3', 'operator-b', NULL, '2026-09-22 09:00:00'),
 (1, '2026-09-23', 'demo-turn-001-4', 'operator-b', NULL, '2026-09-23 09:00:00'),
 (1, '2026-09-24', 'demo-turn-001-5', 'operator-a', NULL, '2026-09-24 09:00:00'),
 (1, '2026-09-25', 'demo-turn-001-6', 'operator-a', NULL, '2026-09-25 09:00:00'),
 (1, '2026-09-26', 'demo-turn-001-7', 'operator-b', NULL, '2026-09-26 09:00:00');
INSERT INTO temperature_record (batch_id, temp_date, celsius, status, recorded_by, created_at) VALUES
 (1, '2026-09-20', 9.5, 'VALID', 'inspector-a', '2026-09-20 10:00:00'),
 (1, '2026-09-21', 10.2, 'VALID', 'inspector-a', '2026-09-21 10:00:00'),
 (1, '2026-09-22', 8.8, 'VALID', 'inspector-b', '2026-09-22 10:00:00'),
 (1, '2026-09-23', 11.0, 'VALID', 'inspector-b', '2026-09-23 10:00:00'),
 (1, '2026-09-24', 9.9, 'VALID', 'inspector-a', '2026-09-24 10:00:00'),
 (1, '2026-09-25', 10.5, 'VALID', 'inspector-a', '2026-09-25 10:00:00'),
 (1, '2026-09-26', 9.1, 'VALID', 'inspector-b', '2026-09-26 10:00:00');
-- 批次2：熟成中，仅前两天有记录
INSERT INTO aging_batch (id, batch_code, entry_at, target_days, status, created_at)
VALUES (2, 'CHEESE-DEMO-002', '2026-10-01 06:30:00', 7, 'AGING', '2026-10-01 06:30:00');
INSERT INTO turn_record (batch_id, turn_date, request_id, operator_name, note, created_at) VALUES
 (2, '2026-10-01', 'demo-turn-002-1', 'operator-c', NULL, '2026-10-01 08:00:00'),
 (2, '2026-10-02', 'demo-turn-002-2', 'operator-c', NULL, '2026-10-02 08:00:00');
INSERT INTO temperature_record (batch_id, temp_date, celsius, status, recorded_by, created_at) VALUES
 (2, '2026-10-01', 10.0, 'VALID', 'inspector-c', '2026-10-01 09:00:00'),
 (2, '2026-10-02', 13.5, 'VALID', 'inspector-c', '2026-10-02 09:00:00');