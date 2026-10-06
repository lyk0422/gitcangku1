-- 合成演示数据：仅内嵌 H2 启动时执行，供本地手工调用演示；测试每用例前清空全部业务表，不受影响。
-- 场景：实验 DEMO-1 已关闭，DP1/DP2 为处理A（结果0、0），DP3/DP4 为处理B（结果100、100），
-- 对 [DP1,DP2,DP3,DP4] 版本1 执行分析应输出 k=2、n=6、p=2/6。

INSERT INTO experiment (id, block_count, status, created_at)
VALUES ('DEMO-1', 2, 'CLOSED', 1700000000000);

INSERT INTO seat (experiment_id, block_no, seat_no, treatment) VALUES
    ('DEMO-1', 1, 1, 'A'),
    ('DEMO-1', 1, 2, 'A'),
    ('DEMO-1', 1, 3, 'B'),
    ('DEMO-1', 1, 4, 'B'),
    ('DEMO-1', 2, 1, 'A'),
    ('DEMO-1', 2, 2, 'A'),
    ('DEMO-1', 2, 3, 'B'),
    ('DEMO-1', 2, 4, 'B');

INSERT INTO allocation
    (experiment_id, participant_id, block_no, seat_no, blind_code, status, assigned_actor, assigned_at, withdrawn_at)
VALUES
    ('DEMO-1', 'DP1', 1, 1, 'SEEDDEMOAAAA', 'ASSIGNED', 'seed', 1700000001000, NULL),
    ('DEMO-1', 'DP2', 1, 2, 'SEEDDEMOBBBB', 'ASSIGNED', 'seed', 1700000001000, NULL),
    ('DEMO-1', 'DP3', 1, 3, 'SEEDDEMOCCCC', 'ASSIGNED', 'seed', 1700000001000, NULL),
    ('DEMO-1', 'DP4', 1, 4, 'SEEDDEMODDDD', 'ASSIGNED', 'seed', 1700000001000, NULL);

INSERT INTO participant_result (experiment_id, participant_id, version, result_value, updated_actor, updated_at)
VALUES
    ('DEMO-1', 'DP1', 1, 0, 'seed', 1700000002000),
    ('DEMO-1', 'DP2', 1, 0, 'seed', 1700000002000),
    ('DEMO-1', 'DP3', 1, 100, 'seed', 1700000002000),
    ('DEMO-1', 'DP4', 1, 100, 'seed', 1700000002000);
