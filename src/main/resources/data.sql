-- 合成演示数据：一个已完成第一步、停在第二步的黑启动计划（id=1），
-- 含一条第二步频率 50.6 Hz 越界被拒绝的历史记录，可用合格读数重试继续。
INSERT INTO black_start_plan (id, status, current_step, created_at, completed_at, terminated_at, termination_reason)
VALUES (1, 'IN_PROGRESS', 2, '2026-10-04 00:00:00', NULL, NULL, NULL);

INSERT INTO black_start_step_attempt
    (id, plan_id, step_no, request_id, outcome, device_status, voltage_v, frequency_hz, power_kw, reject_reason, recorded_at)
VALUES
    (1, 1, 1, 'seed-step1-ok', 'SUCCESS', 'NORMAL', 400.0, 50.0, NULL, NULL, '2026-10-04 00:01:00'),
    (2, 1, 2, 'seed-step2-badfreq', 'REJECTED', 'NORMAL', 400.0, 50.6, NULL, '频率 50.6 Hz 越界，须在 49.5~50.5 Hz 内', '2026-10-04 00:02:00');

INSERT INTO black_start_step_success (plan_id, step_no, attempt_id, recorded_at)
VALUES (1, 1, 1, '2026-10-04 00:01:00');