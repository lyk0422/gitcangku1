-- 本地入口（H2 内存库，MODE=MySQL）启动时初始化的合成演示数据；测试环境不加载本文件。
-- 使用 H2 MERGE 语法保证重复启动（同 JVM 内 DB_CLOSE_DELAY=-1 留存）幂等。

MERGE INTO playout_asset KEY(id) VALUES
    ('demo-fallback', 30000, 0),
    ('demo-movie', 3600000, 0),
    ('demo-news', 20000, 0);

MERGE INTO playout_channel KEY(id) VALUES
    ('demo-ch', 'demo-fallback', 0);

-- 合成授权：demo-ch 对 demo-movie / demo-news 在 2026-01-01 至 2027-01-01（Asia/Shanghai）有效。
-- 1767196800000 = 2026-01-01T00:00:00+08:00，1798732800000 = 2027-01-01T00:00:00+08:00。
INSERT INTO playout_grant (channel_id, asset_id, valid_from_ms, valid_to_ms, created_at_ms)
SELECT 'demo-ch', 'demo-movie', 1767196800000, 1798732800000, 0
    WHERE NOT EXISTS (SELECT 1 FROM playout_grant WHERE channel_id = 'demo-ch' AND asset_id = 'demo-movie');
INSERT INTO playout_grant (channel_id, asset_id, valid_from_ms, valid_to_ms, created_at_ms)
SELECT 'demo-ch', 'demo-news', 1767196800000, 1798732800000, 0
    WHERE NOT EXISTS (SELECT 1 FROM playout_grant WHERE channel_id = 'demo-ch' AND asset_id = 'demo-news');
