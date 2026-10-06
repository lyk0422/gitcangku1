package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.RollbackReason;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 双分区回滚记录数据访问。uk_rollback_task 唯一约束保证每任务至多一条回滚记录。
 */
@Repository
public class BootRollbackRecordRepository {

    private final JdbcTemplate jdbc;

    public BootRollbackRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long taskId, long releaseId, int monitorRound, RollbackReason reason,
                       String rolledBackAtUtc) {
        jdbc.update("INSERT INTO boot_rollback_record (task_id, release_id, monitor_round, reason,"
                        + " rolled_back_at_utc) VALUES (?, ?, ?, ?, ?)",
                taskId, releaseId, monitorRound, reason.name(), rolledBackAtUtc);
    }

    public long countByTask(long taskId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM boot_rollback_record WHERE task_id = ?", Long.class, taskId);
        return count == null ? 0 : count;
    }

    public long countByRelease(long releaseId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM boot_rollback_record WHERE release_id = ?", Long.class, releaseId);
        return count == null ? 0 : count;
    }
}
