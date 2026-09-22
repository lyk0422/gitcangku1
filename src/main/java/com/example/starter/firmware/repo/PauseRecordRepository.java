package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReleasePauseRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 暂停记录数据访问。uk_pause_release_round 保证同一发布单同一轮次最多一条暂停记录。
 */
@Repository
public class PauseRecordRepository {

    private static final RowMapper<ReleasePauseRecord> MAPPER = (rs, rowNum) -> new ReleasePauseRecord(
            rs.getLong("id"), rs.getLong("release_id"), rs.getInt("monitor_round"),
            rs.getLong("trigger_task_id"), rs.getInt("success_count"), rs.getInt("failure_count"),
            rs.getTimestamp("paused_at").toInstant());

    private static final String COLUMNS = "id, release_id, monitor_round, trigger_task_id,"
            + " success_count, failure_count, paused_at";

    private final JdbcTemplate jdbc;

    public PauseRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long releaseId, int monitorRound, long triggerTaskId,
                       int successCount, int failureCount, Instant pausedAt) {
        jdbc.update("INSERT INTO release_pause_record (release_id, monitor_round, trigger_task_id,"
                        + " success_count, failure_count, paused_at) VALUES (?, ?, ?, ?, ?, ?)",
                releaseId, monitorRound, triggerTaskId, successCount, failureCount,
                Timestamp.from(pausedAt));
    }

    public List<ReleasePauseRecord> findByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_pause_record WHERE release_id = ?"
                + " ORDER BY id", MAPPER, releaseId);
    }
}
