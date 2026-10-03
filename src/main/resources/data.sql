-- 本地运行用合成数据（UTC 时刻）。测试库会清空本表数据后自建场景。
-- 合成花序 INF-1001 于 2026-10-04 10:00:00 UTC 首次开放，窗口 24 小时：
-- 次日 09:59 前（不含 10:00）可授粉，2026-10-05 10:00:00 UTC 起窗口过期。

INSERT INTO pollen_batch (batch_no, status, created_at) VALUES
    ('PB-2026-001', 'ACTIVE',   '2026-10-04 08:00:00'),
    ('PB-2026-002', 'ACTIVE',   '2026-10-04 08:05:00'),
    ('PB-2025-OLD', 'DISABLED', '2025-12-01 09:00:00');

INSERT INTO inflorescence (code, first_open_at, window_hours, removed_at, active_operation_id, confirmed_pollen_batch_no, confirmed_at, created_at) VALUES
    ('INF-1001', '2026-10-04 10:00:00', 24, NULL, NULL, NULL, NULL, '2026-10-04 09:30:00'),
    ('INF-1002', '2026-10-04 10:00:00', 24, NULL, NULL, NULL, NULL, '2026-10-04 09:30:00');