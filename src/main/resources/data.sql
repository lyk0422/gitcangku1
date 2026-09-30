-- 本地运行入口的合成种子数据（仅 H2 内存库启动时执行；测试在每个用例前清空业务表，不受影响）。
-- 场景：设备 D 有效读数 100 小时（6000 分钟），保养周期 6500 分钟，大幅修订见证阈值 10 小时（600 分钟）。
-- MERGE 幂等：重复执行不产生重复数据。所有时刻为 UTC。

MERGE INTO equipment (equipment_id, maintenance_period_minutes, revision_threshold_minutes, version, created_at)
KEY (equipment_id)
VALUES ('eq-d', 6500, 600, 2, TIMESTAMP '2026-01-01 00:00:00+00:00');

MERGE INTO reading (equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no,
                    created_at, updated_at)
KEY (equipment_id, reading_id)
VALUES ('eq-d', 'r1', TIMESTAMP '2026-01-01 00:00:00+00:00', 6000, 1,
        TIMESTAMP '2026-01-01 00:00:00+00:00', TIMESTAMP '2026-01-01 00:00:00+00:00');

MERGE INTO reading_revision (equipment_id, reading_id, revision_no, cumulative_minutes, request_id,
                             submitter_id, witness_id, created_at)
KEY (equipment_id, reading_id, revision_no)
VALUES ('eq-d', 'r1', 1, 6000, 'seed-add-d1', NULL, NULL,
        TIMESTAMP '2026-01-01 00:00:00+00:00');
