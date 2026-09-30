-- 紧急储备总账播种：单行账本（id=1），初始储备 10 立方米，已预占 0。
-- MERGE 保证重复初始化（schema 以 IF NOT EXISTS 建表）时不覆盖已划转的账本状态。
MERGE INTO reserve_ledger (id, total_volume, reserved_volume, updated_nanos) KEY (id)
VALUES (1, 10.000, 0.000, 0);
