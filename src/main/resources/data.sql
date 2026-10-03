-- 合成演示数据：一个三检查点计划，便于本地启动后直接调用接口；MERGE 保证重复初始化幂等。
MERGE INTO blast_plan (id, plan_code, area_boundary, status, occupied, created_at) KEY (id) VALUES
    (1, 'PLAN-DEMO', '采石场A区：东至运输道路，南至排土场，西至山体，北至警戒岗哨，半径300米', 'ACTIVE', FALSE, '2026-10-01 00:00:00');
MERGE INTO plan_checkpoint (id, plan_id, checkpoint_code, seq_no) KEY (id) VALUES
    (1, 1, 'CP-NORTH', 1),
    (2, 1, 'CP-EAST', 2),
    (3, 1, 'CP-GATE', 3);