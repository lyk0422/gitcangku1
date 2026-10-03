INSERT INTO firing_program_version (id, name, version_no, created_at)
VALUES (1, 'DEMO-GLAZE', 1, '2026-01-01 00:00:00');

INSERT INTO firing_program_segment (program_version_id, seq, start_time, end_time, target_temp_c, tolerance_c) VALUES
(1, 1, '2026-01-01 00:00:00', '2026-01-01 00:20:00', 600.00, 15.00),
(1, 2, '2026-01-01 00:20:00', '2026-01-01 00:40:00', 800.00, 12.00),
(1, 3, '2026-01-01 00:40:00', '2026-01-01 01:00:00', 1050.00, 10.00);