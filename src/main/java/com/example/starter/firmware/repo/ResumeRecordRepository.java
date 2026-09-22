package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ResumeRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 人工恢复记录数据访问。历史只增不改。
 */
@Repository
public class ResumeRecordRepository {

    private static final RowMapper<ResumeRecord> MAPPER = (rs, rowNum) -> new ResumeRecord(
            rs.getLong("id"), rs.getLong("release_id"), rs.getInt("new_round"),
            rs.getString("reason"), rs.getString("resumed_at_utc"));

    private static final String COLUMNS = "id, release_id, new_round, reason, resumed_at_utc";

    private final JdbcTemplate jdbc;

    public ResumeRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long releaseId, int newRound, String reason, String resumedAtUtc) {
        jdbc.update("INSERT INTO release_resume_record (release_id, new_round, reason, resumed_at_utc)"
                + " VALUES (?, ?, ?, ?)", releaseId, newRound, reason, resumedAtUtc);
    }

    public List<ResumeRecord> findByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_resume_record WHERE release_id = ? ORDER BY id",
                MAPPER, releaseId);
    }
}
