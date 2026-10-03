-- 合成数据：一个待执行的黑启动计划，从第 1 步开始，供本地演示与联调使用。
INSERT INTO black_start_plan (id, name, status, current_step, created_at)
VALUES (1, '合成黑启动计划-01', 'IN_PROGRESS', 1, TIMESTAMP '2026-01-01 00:00:00');