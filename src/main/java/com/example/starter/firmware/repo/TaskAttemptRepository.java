package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.TaskAttempt;
import com.example.starter.firmware.domain.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 任务尝试代次数据访问。uk_attempt_task_no 保证同任务代次唯一；旧代次只读不改。
 */
@Repository
public class TaskAttemptRepository {

    private static final RowMapper<TaskAttempt> MAPPER = (rs, rowNum) -> new TaskAttempt(
            rs.getLong("id"), rs.getLong("task_id"), rs.getInt("attempt_no"),
            TaskStatus.valueOf(rs.getString("status")),
            rs.getString("created_at_utc"), rs.getString("finalized_at_utc"));

    private static final String COLUMNS = "id, task_id, attempt_no, status, created_at_utc, finalized_at_utc";

    private final JdbcTemplate jdbc;

    public TaskAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long taskId, int attemptNo, String createdAtUtc) {
        jdbc.update("INSERT INTO task_attempt (task_id, attempt_no, status, created_at_utc)"
                + " VALUES (?, ?, 'PENDING', ?)", taskId, attemptNo, createdAtUtc);
    }

    /**
     * 当前（最大代次）尝试。
     */
    public Optional<TaskAttempt> findCurrent(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_attempt WHERE task_id = ?"
                + " ORDER BY attempt_no DESC LIMIT 1", MAPPER, taskId).stream().findFirst();
    }

    public List<TaskAttempt> findByTask(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_attempt WHERE task_id = ?"
                + " ORDER BY attempt_no", MAPPER, taskId);
    }

    /**
     * 更新指定代次状态；终结时同时写入终结时刻（UTC）。
     */
    public void updateStatus(long taskId, int attemptNo, TaskStatus status, String finalizedAtUtc) {
        jdbc.update("UPDATE task_attempt SET status = ?, finalized_at_utc = ?"
                + " WHERE task_id = ? AND attempt_no = ?", status.name(), finalizedAtUtc, taskId, attemptNo);
    }

    /**
     * 将发布单下未终结尝试（PENDING/INSTALLABLE/INTEGRITY_FAILED）批量置为 CANCELLED。
     */
    public int cancelUnfinishedByRelease(long releaseId, String finalizedAtUtc) {
        return jdbc.update("UPDATE task_attempt SET status = 'CANCELLED', finalized_at_utc = ?"
                        + " WHERE status IN ('PENDING', 'INSTALLABLE', 'INTEGRITY_FAILED')"
                        + " AND task_id IN (SELECT id FROM rollout_task WHERE release_id = ?)",
                finalizedAtUtc, releaseId);
    }
}
