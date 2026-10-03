-- 合成试剂资料，仅供本地运行演示，非真实数据
INSERT INTO reagent_bottle (id, lot_number, capacity_ml, remaining_ml, supplier_expiry_utc, created_at_utc) VALUES
    (1, 'LOT-2026-0001', 500.0, 500.0, '2027-06-30 00:00:00', '2026-10-01 00:00:00'),
    (2, 'LOT-2026-0002', 100.0, 100.0, '2026-12-31 00:00:00', '2026-10-01 00:00:00');

INSERT INTO bottle_event (bottle_id, event_type, occurred_at_utc, detail) VALUES
    (1, 'RECEIVE', '2026-10-01 00:00:00', '合成接收记录'),
    (2, 'RECEIVE', '2026-10-01 00:00:00', '合成接收记录');