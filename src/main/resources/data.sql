-- 合成桥梁配置：最大总质量35吨（35000千克），单次通行预计窗口10分钟
MERGE INTO bridge_config (id, max_mass_kg, window_minutes, updated_at) KEY(id)
VALUES (1, 35000, 10, TIMESTAMP '2026-01-01 00:00:00');