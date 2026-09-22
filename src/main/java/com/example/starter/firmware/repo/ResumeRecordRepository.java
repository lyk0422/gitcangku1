package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReleaseResumeRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 恢复记录数据访问。uk_resume_release_round 保证同一发布单同一新轮次最多一条恢复记录。
 */
@Repository
public class ResumeRecordRepository {

    private static final RowMapper<ReleaseResumeRecord> MAPPER = (rs, rowNum) -> new ReleaseResumeRecord(
            rs.getLong("id"), rs.getLong("release_id"), rs.getInt("monitor_round"),
            rs.getInt("version"), rs.getString("reason"),
            rs.getTimestamp("resumed_at").toInstant());

    private static final String COLUMNS = "id, release_id, monitor_round, version, reason, resumed_at";

    private final JdbcTemplate jdbc;

    public ResumeRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long releaseId, int monitorRound, int version, String reason, Instant resumedAt) {
        jdbc.update("INSERT INTO release_resume_record (release_id, monitor_round, version, reason, resumed_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                releaseId, monitorRound, version, reason, Timestamp.from(resumedAt));
    }

    public List<ReleaseResumeRecord> findByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_resume_record WHERE release_id = ?"
                + " ORDER BY id", MAPPER, releaseId);
    }
}
