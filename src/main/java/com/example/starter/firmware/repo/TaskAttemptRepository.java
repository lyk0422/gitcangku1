package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.AttemptStatus;
import com.example.starter.firmware.domain.TaskAttempt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 任务尝试代次数据访问。代次记录只增不改，终态（INTEGRITY_FAILED/INSTALLABLE）后再无 UPDATE。
 */
@Repository
public class TaskAttemptRepository {

    private static final RowMapper<TaskAttempt> MAPPER = (rs, rowNum) -> new TaskAttempt(
            rs.getLong("id"), rs.getLong("task_id"), rs.getInt("attempt_no"),
            rs.getLong("release_id"), rs.getInt("release_version"),
            AttemptStatus.valueOf(rs.getString("status")),
            rs.getInt("required_shards"), rs.getInt("received_shards"),
            rs.getString("package_digest"), rs.getString("aggregate_digest"),
            rs.getString("failure_reason"),
            rs.getString("created_at_utc"), rs.getString("finalized_at_utc"));

    private static final String COLUMNS = "id, task_id, attempt_no, release_id, release_version, status,"
            + " required_shards, received_shards, package_digest, aggregate_digest, failure_reason,"
            + " created_at_utc, finalized_at_utc";

    private final JdbcTemplate jdbc;

    public TaskAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertReceiving(long taskId, int attemptNo, long releaseId, int releaseVersion,
                                int requiredShards, String packageDigest, String createdAtUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO task_attempt (task_id, attempt_no, release_id, release_version, status,"
                            + " required_shards, received_shards, package_digest, created_at_utc)"
                            + " VALUES (?, ?, ?, ?, 'RECEIVING', ?, 0, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, taskId);
            ps.setInt(2, attemptNo);
            ps.setLong(3, releaseId);
            ps.setInt(4, releaseVersion);
            ps.setInt(5, requiredShards);
            ps.setString(6, packageDigest);
            ps.setString(7, createdAtUtc);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<TaskAttempt> findByTaskAndAttempt(long taskId, int attemptNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_attempt WHERE task_id = ? AND attempt_no = ?",
                MAPPER, taskId, attemptNo).stream().findFirst();
    }

    /**
     * 终态判定：仅 RECEIVING 代次可更新，返回影响行数。重放开新事务再次到达时为幂等空更新。
     */
    public int finalizeInstallable(long taskId, int attemptNo, int receivedShards, String aggregateDigest,
                                   String finalizedAtUtc) {
        return jdbc.update("UPDATE task_attempt SET status = 'INSTALLABLE', received_shards = ?,"
                + " aggregate_digest = ?, finalized_at_utc = ?"
                + " WHERE task_id = ? AND attempt_no = ? AND status = 'RECEIVING'",
                receivedShards, aggregateDigest, finalizedAtUtc, taskId, attemptNo);
    }

    public int finalizeIntegrityFailed(long taskId, int attemptNo, int receivedShards, String failureReason,
                                       String finalizedAtUtc) {
        return jdbc.update("UPDATE task_attempt SET status = 'INTEGRITY_FAILED', received_shards = ?,"
                + " failure_reason = ?, finalized_at_utc = ?"
                + " WHERE task_id = ? AND attempt_no = ? AND status = 'RECEIVING'",
                receivedShards, failureReason, finalizedAtUtc, taskId, attemptNo);
    }

    /**
     * 任务撤销：未安装代次（RECEIVING/INTEGRITY_FAILED/INSTALLABLE）转 CANCELLED，已安装代次不动。
     */
    public int cancelAttempt(long taskId, int attemptNo) {
        return jdbc.update("UPDATE task_attempt SET status = 'CANCELLED'"
                + " WHERE task_id = ? AND attempt_no = ?"
                + " AND status IN ('RECEIVING', 'INTEGRITY_FAILED', 'INSTALLABLE')", taskId, attemptNo);
    }

    /**
     * 发布单取消：该发布单下所有未安装代次转 CANCELLED，已安装（随任务 SUCCESS/FAILED）代次不动。
     */
    public int cancelOpenByRelease(long releaseId) {
        return jdbc.update("UPDATE task_attempt SET status = 'CANCELLED' WHERE release_id = ?"
                + " AND status IN ('RECEIVING', 'INTEGRITY_FAILED', 'INSTALLABLE')", releaseId);
    }

    public List<TaskAttempt> findByTaskOrdered(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_attempt WHERE task_id = ? ORDER BY attempt_no",
                MAPPER, taskId);
    }
}
