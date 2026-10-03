-- 合成演示数据：标签 TAG-L 登记在站点 ST-A，便于本地启动后直接体验业务链。
MERGE INTO tag_ownership KEY(tag_code) VALUES
    ('TAG-L', 'ST-A', TIMESTAMP '2026-01-01 00:00:00');