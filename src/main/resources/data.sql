-- 合成演示数据：剧目HAMLET一版已冻结编排（顺序1、2、3）与一版草稿，及一场未开始的排练
INSERT INTO cue_version (id, play_code, version_no, status, created_at, frozen_at) VALUES
    (1, 'HAMLET', 1, 'FROZEN', '2026-10-01 12:00:00', '2026-10-01 12:30:00'),
    (2, 'HAMLET', 2, 'DRAFT', '2026-10-02 09:00:00', NULL);

INSERT INTO cue_item (version_id, seq_no, cue_type, trigger_phrase, duration_seconds) VALUES
    (1, 1, 'LIGHT', '幕启', 30),
    (1, 2, 'SOUND', '独白开始', 45),
    (1, 3, 'LIGHT', '幕落', 20),
    (2, 1, 'LIGHT', '幕启', 25);

INSERT INTO rehearsal_session (id, session_name, version_id, status, last_executed_seq, created_at) VALUES
    (1, '合成彩排A组', 1, 'NOT_STARTED', 0, '2026-10-03 10:00:00');