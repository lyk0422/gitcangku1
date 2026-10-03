-- 合成演示数据：校准 08:00(UTC) 通过，次日 07:59 结束的任务可绑定，次日 08:00 开始的任务被拒绝。
-- 使用高号段固定主键避免与自增冲突，重复启动时先清理，保证幂等。
DELETE FROM task_binding;
DELETE FROM receive_task;
DELETE FROM calibration_record;
DELETE FROM antenna;

INSERT INTO antenna (id, antenna_code) VALUES (1001, 'ANT-01'), (1002, 'ANT-02');

-- 校准记录：2026-01-01 08:00:00 UTC 通过，有效期至 2026-01-02 08:00:00 UTC
INSERT INTO calibration_record
    (id, antenna_id, calibrated_at, azimuth_deviation, elevation_deviation, passed, valid_until, status, created_at)
VALUES
    (1001, 1001, '2026-01-01 08:00:00', 0.10, -0.15, TRUE, '2026-01-02 08:00:00', 'ACTIVE', '2026-01-01 08:00:00');

-- 任务一：2026-01-01 12:00 至次日 07:59，完全落在有效期内，可绑定
INSERT INTO receive_task (id, task_code, antenna_id, start_at, end_at, created_at)
VALUES (1001, 'TASK-OVERNIGHT-OK', 1001, '2026-01-01 12:00:00', '2026-01-02 07:59:00', '2026-01-01 09:00:00');

-- 任务二：次日 08:00 才开始，超出有效期，绑定将被拒绝（422）
INSERT INTO receive_task (id, task_code, antenna_id, start_at, end_at, created_at)
VALUES (1002, 'TASK-TOO-LATE', 1001, '2026-01-02 08:00:00', '2026-01-02 09:00:00', '2026-01-01 09:00:00');