-- 合成种子数据：调度方发布的 18:00-19:00（UTC，左闭右开）削峰邀约。
-- 时间字面量带 +00:00 偏移，H2/MySQL 会按会话时区换算入库，经 JDBC 读回的 UTC 瞬时不变。
-- H2 内存库每次启动均为全新数据，此脚本随本地/测试启动幂等执行一次。
INSERT INTO demand_offer (id, start_time, end_time, created_at)
SELECT 'offer-20261001-1800',
       TIMESTAMP '2026-10-01 18:00:00+00:00',
       TIMESTAMP '2026-10-01 19:00:00+00:00',
       TIMESTAMP '2026-10-01 10:00:00+00:00'
WHERE NOT EXISTS (SELECT 1 FROM demand_offer WHERE id = 'offer-20261001-1800');
