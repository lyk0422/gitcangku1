-- 本地演示合成数据（仅本地 H2 入口使用，测试库不加载，相互隔离）。
-- 事件 INC-DEMO-RISK：S2、COMMANDING、指挥人 commander-demo；
-- 风险模型 R=100、L=20；屏障 B-A/B-B 各削减 1/2（任务已 DONE），B-C 削减 1/5（任务 OPEN）。
-- 当前残余 = 100 × 1/2 × 1/2 = 25 > 20，请求遏制将被拒绝（422）；
-- 完成 T-C 后残余 = 100 × 1/2 × 1/2 × 4/5 = 20 <= 20，允许遏制；撤销 B-C 后残余回到 25，拒绝。

INSERT INTO incidents (incident_key, severity, summary, reporter, status, commander, deadline_at,
    created_at, updated_at)
VALUES ('INC-DEMO-RISK', 'S2', '合成残余风险演示事件', 'synthetic', 'COMMANDING', 'commander-demo',
    '2099-01-01 00:00:00.000000', '2026-01-01 00:00:00.000000', '2026-01-01 00:00:00.000000');

INSERT INTO incident_status_history (incident_id, from_status, to_status, actor, occurred_at) VALUES
(1, NULL, 'REPORTED', 'synthetic', '2026-01-01 00:00:00.000000'),
(1, 'REPORTED', 'COMMANDING', 'commander-demo', '2026-01-01 00:05:00.000000');

INSERT INTO incident_tasks (incident_id, task_key, group_code, title, status, created_by, done_by,
    done_at, cancelled_by, cancelled_at, created_at, updated_at) VALUES
(1, 'T-A', 'NET', '隔离受影响网段', 'DONE', 'commander-demo', 'commander-demo',
    '2026-01-01 01:00:00.000000', NULL, NULL, '2026-01-01 00:10:00.000000', '2026-01-01 01:00:00.000000'),
(1, 'T-B', 'HOST', '下线失陷主机', 'DONE', 'commander-demo', 'commander-demo',
    '2026-01-01 01:05:00.000000', NULL, NULL, '2026-01-01 00:15:00.000000', '2026-01-01 01:05:00.000000'),
(1, 'T-C', 'APP', '阻断异常流量', 'OPEN', 'commander-demo', NULL, NULL, NULL, NULL,
    '2026-01-01 00:20:00.000000', '2026-01-01 00:20:00.000000');

INSERT INTO incident_risk_models (incident_id, initial_risk, residual_limit, version, contained_at,
    frozen_residual_num, frozen_residual_den, created_at, updated_at)
VALUES (1, 100, 20, 4, NULL, NULL, NULL, '2026-01-01 00:30:00.000000', '2026-01-01 00:30:00.000000');

INSERT INTO incident_risk_barriers (model_id, barrier_key, task_key, reduce_p, reduce_q, revoked,
    created_at, updated_at) VALUES
(1, 'B-A', 'T-A', 1, 2, FALSE, '2026-01-01 00:31:00.000000', '2026-01-01 00:31:00.000000'),
(1, 'B-B', 'T-B', 1, 2, FALSE, '2026-01-01 00:32:00.000000', '2026-01-01 00:32:00.000000'),
(1, 'B-C', 'T-C', 1, 5, FALSE, '2026-01-01 00:33:00.000000', '2026-01-01 00:33:00.000000');
