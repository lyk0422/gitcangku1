-- 合成演示数据：本地入口启动即可体验轮换与普通公告；均为合成数据，不含真实信息。
-- 使用 INSERT ... WHERE NOT EXISTS 保证重复初始化（同库重启上下文）时不产生主键冲突。

-- 轮换公告：固定有序创意 ["A","B"]，访客确认次数取模轮换
INSERT INTO campaign (campaign_id, daily_total_cap, per_visitor_daily_cap, creative_ids, created_at_utc)
SELECT 'demo-rotation', 100, 10, '["A","B"]', 1790000000000
WHERE NOT EXISTS (SELECT 1 FROM campaign WHERE campaign_id = 'demo-rotation');

-- 三创意轮换公告
INSERT INTO campaign (campaign_id, daily_total_cap, per_visitor_daily_cap, creative_ids, created_at_utc)
SELECT 'demo-rotation-abc', 100, 10, '["A","B","C"]', 1790000000000
WHERE NOT EXISTS (SELECT 1 FROM campaign WHERE campaign_id = 'demo-rotation-abc');

-- 普通公告：未配置轮换创意，仅接受普通申请
INSERT INTO campaign (campaign_id, daily_total_cap, per_visitor_daily_cap, creative_ids, created_at_utc)
SELECT 'demo-plain', 100, 10, NULL, 1790000000000
WHERE NOT EXISTS (SELECT 1 FROM campaign WHERE campaign_id = 'demo-plain');
