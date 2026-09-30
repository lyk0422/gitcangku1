package com.example.starter.cue.repo;

import java.util.List;

import com.example.starter.cue.domain.CueSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 冻结快照数据访问；快照在排练开始时一次性写入，之后只读。
 */
@Repository
public class CueSnapshotRepository {

    private final JdbcTemplate jdbcTemplate;

    public CueSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 写入一条冻结提示点。
     */
    public void insert(long sessionId, int seqNo, String cueType, String triggerWord, int durationSeconds) {
        jdbcTemplate.update(
                "INSERT INTO cue_snapshot (session_id, seq_no, cue_type, trigger_word, duration_seconds) "
                        + "VALUES (?, ?, ?, ?, ?)",
                sessionId, seqNo, cueType, triggerWord, durationSeconds);
    }

    /**
     * 查询场次冻结序列，按序号升序。
     */
    public List<CueSnapshot> findBySession(long sessionId) {
        return jdbcTemplate.query(
                "SELECT id, session_id, seq_no, cue_type, trigger_word, duration_seconds "
                        + "FROM cue_snapshot WHERE session_id = ? ORDER BY seq_no ASC",
                (rs, rowNum) -> new CueSnapshot(
                        rs.getLong("id"),
                        rs.getLong("session_id"),
                        rs.getInt("seq_no"),
                        rs.getString("cue_type"),
                        rs.getString("trigger_word"),
                        rs.getInt("duration_seconds")),
                sessionId);
    }
}
