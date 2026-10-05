package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.RollbackReason;
import com.example.starter.firmware.domain.RollbackRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 双分区试启动回滚记录数据访问。唯一约束 uk_rollback_task 保证每个任务至多一条，历史只增不改。
 */
@Repository
public class RollbackRecordRepository {

    private static final RowMapper<RollbackRecord> MAPPER = (rs, rowNum) -> new RollbackRecord(
            rs.getLong("id"), rs.getLong("task_id"), rs.getLong("release_id"), rs.getString("device_id"),
            rs.getString("kept_partition"), rs.getString("kept_version"),
            RollbackReason.valueOf(rs.getString("reason")), rs.getString("rolled_back_at_utc"));

    private static final String COLUMNS = "id, task_id, release_id, device_id, kept_partition,"
            + " kept_version, reason, rolled_back_at_utc";

    private final JdbcTemplate jdbc;

    public RollbackRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long taskId, long releaseId, String deviceId, String keptPartition,
                       String keptVersion, RollbackReason reason, String rolledBackAtUtc) {
        jdbc.update("INSERT INTO rollout_rollback_record"
                        + " (task_id, release_id, device_id, kept_partition, kept_version, reason,"
                        + " rolled_back_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?)",
                taskId, releaseId, deviceId, keptPartition, keptVersion, reason.name(), rolledBackAtUtc);
    }

    public List<RollbackRecord> findByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_rollback_record WHERE release_id = ?"
                + " ORDER BY id", MAPPER, releaseId);
    }

    public long countByTask(long taskId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_rollback_record WHERE task_id = ?", Long.class, taskId);
        return count == null ? 0 : count;
    }

    public long countByRelease(long releaseId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_rollback_record WHERE release_id = ?", Long.class, releaseId);
        return count == null ? 0 : count;
    }
}