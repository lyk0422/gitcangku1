-- 合成演示数据：一个已人工封闭的控制区 DEMO-1，便于本地启动后直接走解除审核流程。
-- 时间均为 UTC；数值为合成数据，不构成真实安全预报。

INSERT INTO region_state (region_id, status, updated_at)
SELECT 'DEMO-1', 'CLOSED', TIMESTAMP '2026-01-01 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM region_state WHERE region_id = 'DEMO-1');

INSERT INTO closure_command (region_id, source, reason, request_id, created_at)
SELECT 'DEMO-1', 'MANUAL', 'seed: synthetic avalanche-control closure', 'seed-closure-demo-1', TIMESTAMP '2026-01-01 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM closure_command WHERE request_id = 'seed-closure-demo-1');