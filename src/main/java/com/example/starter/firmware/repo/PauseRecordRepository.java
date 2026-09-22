package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.PauseRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 自动暂停记录数据访问。唯一约束 uk_pause_release_round 保证每轮至多一条，历史只增不改。
 */
@Repository
public class PauseRecordRepository {

    private static final RowMapper<PauseRecord> MAPPER = (rs, rowNum) -> new PauseRecord(
            rs.getLong("id"), rs.getLong("release_id"), rs.getInt("monitor_round"),
            rs.getLong("trigger_task_id"), rs.getInt("success_count"), rs.getInt("failed_count"),
            rs.getString("paused_at_utc"));

    private static final String COLUMNS = "id, release_id, monitor_round, trigger_task_id,"
            + " success_count, failed_count, paused_at_utc";

    private final JdbcTemplate jdbc;

    public PauseRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long releaseId, int monitorRound, long triggerTaskId,
                       int successCount, int failedCount, String pausedAtUtc) {
        jdbc.update("INSERT INTO release_pause_record"
                        + " (release_id, monitor_round, trigger_task_id, success_count, failed_count, paused_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                releaseId, monitorRound, triggerTaskId, successCount, failedCount, pausedAtUtc);
    }

    public List<PauseRecord> findByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_pause_record WHERE release_id = ? ORDER BY id",
                MAPPER, releaseId);
    }

    public long countByRelease(long releaseId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_pause_record WHERE release_id = ?", Long.class, releaseId);
        return count == null ? 0 : count;
    }
}
