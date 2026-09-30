-- 合成泊位数据；静态水深单位米。可重入执行。
INSERT INTO berth (code, static_depth, created_at)
SELECT 'B-01', 8.80, CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM berth WHERE code = 'B-01');
INSERT INTO berth (code, static_depth, created_at)
SELECT 'B-02', 9.50, CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM berth WHERE code = 'B-02');
-- 全局分配锁唯一行。
INSERT INTO alloc_lock (id, name)
SELECT 1, 'GLOBAL' WHERE NOT EXISTS (SELECT 1 FROM alloc_lock WHERE id = 1);
