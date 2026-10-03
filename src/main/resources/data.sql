-- 合成演示数据：遗址、层位、样本、操作人均为虚构，仅用于本地启动演示。
-- 使用 MERGE 保证多上下文重复执行时幂等。
MERGE INTO stratum KEY(id) VALUES
(1, 'SITE-SYNTH-01', 'L', '合成遗址L层（新石器时代演示层）', '2026-01-01 00:00:00'),
(2, 'SITE-SYNTH-01', 'M', '合成遗址M层（商周演示层）', '2026-01-01 00:00:00');

MERGE INTO sample KEY(id) VALUES
(1, 'SYN-L-0001', 1, 'G-01', '2026-01-02 08:00:00', 'COLLECTED', NULL),
(2, 'SYN-L-0002', 1, 'G-02', '2026-01-02 08:05:00', 'COLLECTED', NULL),
(3, 'SYN-M-0001', 2, 'G-01', '2026-01-02 09:00:00', 'COLLECTED', NULL);

ALTER TABLE stratum ALTER COLUMN id RESTART WITH 100;
ALTER TABLE sample ALTER COLUMN id RESTART WITH 100;