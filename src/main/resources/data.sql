-- 本地入口合成演示数据：一个三点检查的清场计划，测试库独立命名并自行清理，不依赖本数据。
INSERT INTO blast_plan (name, boundary, status, occupied, current_round, created_at)
VALUES ('DEMO-QUARRY-A', '采场东区 500m 警戒圈', 'OPEN', 0, 1, '2026-01-01 00:00:00');
INSERT INTO checkpoint (plan_id, code, description) VALUES
    (1, 'CP-N', '北侧入口'),
    (1, 'CP-E', '东侧便道'),
    (1, 'CP-S', '南侧坡道');