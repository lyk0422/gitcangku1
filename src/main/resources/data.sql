-- 合成基础数据：站点与动物均为合成编号，不含任何真实位置数据。
-- 仅本地 H2 与测试库初始化时执行。
MERGE INTO site (id, site_code, name, created_at) KEY (id) VALUES
    (1, 'S1', '合成站点一', '2026-01-01 00:00:00'),
    (2, 'S2', '合成站点二', '2026-01-01 00:00:00');

MERGE INTO animal (id, animal_code, species, created_at) KEY (id) VALUES
    (1, 'A1', 'SYNTHETIC-FOX', '2026-01-01 00:00:00'),
    (2, 'A2', 'SYNTHETIC-HARE', '2026-01-01 00:00:00');
