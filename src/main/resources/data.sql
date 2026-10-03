-- 合成电站初始化数据：一台合成机组与一条待确认限发指令，便于本地启动后直接联调。
-- 时间为 UTC；测试启动时会清空场景数据，不依赖本文件内容。

INSERT INTO unit_lock (unit_id, created_at)
SELECT 'HYDRO-UNIT-01', TIMESTAMP '2026-01-01 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM unit_lock WHERE unit_id = 'HYDRO-UNIT-01');

INSERT INTO curtailment_instruction
    (instruction_no, unit_id, start_utc, end_utc, max_power_kw, status, version, created_at, updated_at)
SELECT 'INS-SYNTH-0001', 'HYDRO-UNIT-01',
       TIMESTAMP '2027-01-01 02:00:00', TIMESTAMP '2027-01-01 03:00:00',
       100.000, 'PENDING', 0, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00'
WHERE NOT EXISTS (SELECT 1 FROM curtailment_instruction WHERE instruction_no = 'INS-SYNTH-0001');