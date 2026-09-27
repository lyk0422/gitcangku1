package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.TaskAttempt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 任务尝试代次数据访问。判定字段（verdict/verdict_reason/actual_aggregate/decided_at_utc）只在判定时写入一次，
 * 之后不再改写；received_count 随接收证据递增。
 */
@Repository
public class AttemptRepository {

    private static final RowMapper<TaskAttempt> MAPPER = (rs, rowNum) -> new TaskAttempt(
            rs.getLong("id"), rs.getLong("task_id"), rs.getInt("attempt_no"),
            rs.getLong("release_id"), rs.getString("to_version"),
            rs.getString("expected_aggregate"), rs.getInt("required_count"), rs.getInt("received_count"),
            rs.getString("verdict"), rs.getString("verdict_reason"), rs.getString("actual_aggregate"),
            rs.getString("decided_at_utc"), rs.getString("created_at_utc"));

    private static final String COLUMNS = "id, task_id, attempt_no, release_id, to_version, expected_aggregate,"
            + " required_count, received_count, verdict, verdict_reason, actual_aggregate, decided_at_utc,"
            + " created_at_utc";

    private final JdbcTemplate jdbc;

    public AttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long taskId, int attemptNo, long releaseId, String toVersion,
                       String expectedAggregate, int requiredCount, String createdAtUtc) {
        jdbc.update("INSERT INTO task_attempt (task_id, attempt_no, release_id, to_version,"
                        + " expected_aggregate, required_count, created_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?)",
                taskId, attemptNo, releaseId, toVersion, expectedAggregate, requiredCount, createdAtUtc);
    }

    public Optional<TaskAttempt> find(long taskId, int attemptNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_attempt WHERE task_id = ? AND attempt_no = ?",
                MAPPER, taskId, attemptNo).stream().findFirst();
    }

    public List<TaskAttempt> findByTask(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_attempt WHERE task_id = ? ORDER BY attempt_no",
                MAPPER, taskId);
    }

    /**
     * 已接收分片数加一；调用方持有任务行锁。
     */
    public void incrementReceived(long taskId, int attemptNo) {
        jdbc.update("UPDATE task_attempt SET received_count = received_count + 1"
                + " WHERE task_id = ? AND attempt_no = ?", taskId, attemptNo);
    }

    /**
     * 固化判定结果；只在判定时写入一次，之后不改写。
     */
    public void decide(long taskId, int attemptNo, String verdict, String reason,
                       String actualAggregate, String decidedAtUtc) {
        jdbc.update("UPDATE task_attempt SET verdict = ?, verdict_reason = ?, actual_aggregate = ?,"
                        + " decided_at_utc = ? WHERE task_id = ? AND attempt_no = ?",
                verdict, reason, actualAggregate, decidedAtUtc, taskId, attemptNo);
    }
}
