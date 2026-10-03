-- 合成观测数据：仅用于本地演示与开发，不含真实观测记录
-- PLOT-OPEN：空闲样方；PLOT-FULL：12株活跃（11存活+1待复核）用于演示容量与封存拒绝；PLOT-REVIEW：含待复核植株用于演示复核
INSERT INTO plot (id, plot_code, capacity, status, sealed_at) VALUES
    (1, 'PLOT-OPEN', 12, 'ACTIVE', NULL),
    (2, 'PLOT-FULL', 12, 'ACTIVE', NULL),
    (3, 'PLOT-REVIEW', 12, 'ACTIVE', NULL);

INSERT INTO transplant (id, request_id, plant_code, nursery, plot_id, transplanted_at, status) VALUES
    (101, 'seed-req-001', 'CORAL-F001', '三亚合成苗圃A', 2, '2026-09-01 02:00:00', 'ALIVE'),
    (102, 'seed-req-002', 'CORAL-F002', '三亚合成苗圃A', 2, '2026-09-01 02:05:00', 'ALIVE'),
    (103, 'seed-req-003', 'CORAL-F003', '三亚合成苗圃A', 2, '2026-09-01 02:10:00', 'ALIVE'),
    (104, 'seed-req-004', 'CORAL-F004', '三亚合成苗圃A', 2, '2026-09-01 02:15:00', 'ALIVE'),
    (105, 'seed-req-005', 'CORAL-F005', '三亚合成苗圃B', 2, '2026-09-01 02:20:00', 'ALIVE'),
    (106, 'seed-req-006', 'CORAL-F006', '三亚合成苗圃B', 2, '2026-09-01 02:25:00', 'ALIVE'),
    (107, 'seed-req-007', 'CORAL-F007', '三亚合成苗圃B', 2, '2026-09-01 02:30:00', 'ALIVE'),
    (108, 'seed-req-008', 'CORAL-F008', '三亚合成苗圃B', 2, '2026-09-01 02:35:00', 'ALIVE'),
    (109, 'seed-req-009', 'CORAL-F009', '西沙合成苗圃C', 2, '2026-09-01 02:40:00', 'ALIVE'),
    (110, 'seed-req-010', 'CORAL-F010', '西沙合成苗圃C', 2, '2026-09-01 02:45:00', 'ALIVE'),
    (111, 'seed-req-011', 'CORAL-F011', '西沙合成苗圃C', 2, '2026-09-01 02:50:00', 'ALIVE'),
    (112, 'seed-req-012', 'CORAL-F012', '西沙合成苗圃C', 2, '2026-09-01 02:55:00', 'PENDING'),
    (113, 'seed-req-013', 'CORAL-R001', '三亚合成苗圃A', 3, '2026-09-02 03:00:00', 'ALIVE'),
    (114, 'seed-req-014', 'CORAL-R002', '三亚合成苗圃A', 3, '2026-09-02 03:05:00', 'PENDING');

INSERT INTO review (id, request_id, transplant_id, result, reviewed_at) VALUES
    (1001, 'seed-review-001', 113, 'ALIVE', '2026-09-03 04:00:00');