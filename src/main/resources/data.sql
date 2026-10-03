-- 合成电站初始化数据；仅用于本地运行与测试，不对应真实电站。
INSERT INTO power_unit (unit_id, plant_name, unit_name, created_utc) VALUES
    ('SYNTH-U1', '合成水电站', '1号水轮发电机组', TIMESTAMP '2026-01-01 00:00:00'),
    ('SYNTH-U2', '合成水电站', '2号水轮发电机组', TIMESTAMP '2026-01-01 00:00:00');