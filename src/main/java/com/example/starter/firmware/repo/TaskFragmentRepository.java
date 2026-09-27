package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.TaskFragmentReceipt;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 任务分片接收证据数据访问。唯一约束 uk_task_attempt_seq 保证同一任务同一代次同一序号
 * 最多一条证据：并发提交同序号分片时，仅一笔插入成功，其余抛 {@link DuplicateKeyException}。
 * 证据只增不改，重新拉取产生新代次时旧代次行永久保留。
 */
@Repository
public class TaskFragmentRepository {

    private static final RowMapper<TaskFragmentReceipt> MAPPER = (rs, rowNum) -> new TaskFragmentReceipt(
            rs.getLong("id"), rs.getLong("task_id"), rs.getInt("attempt"), rs.getInt("seq_no"),
            rs.getString("received_digest"), rs.getString("expected_digest"),
            rs.getInt("release_version"), rs.getString("received_at_utc"), rs.getString("request_id"));

    private static final String COLUMNS = "id, task_id, attempt, seq_no, received_digest, expected_digest,"
            + " release_version, received_at_utc, request_id";

    private final JdbcTemplate jdbc;

    public TaskFragmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一条接收证据；同任务同代次同序号冲突时抛 DuplicateKeyException，由服务层裁决。
     */
    public void insert(long taskId, int attempt, int seqNo, String receivedDigest, String expectedDigest,
                       int releaseVersion, String receivedAtUtc, String requestId) {
        jdbc.update("INSERT INTO task_fragment_receipt"
                        + " (task_id, attempt, seq_no, received_digest, expected_digest,"
                        + " release_version, received_at_utc, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                taskId, attempt, seqNo, receivedDigest, expectedDigest,
                releaseVersion, receivedAtUtc, requestId);
    }

    /**
     * 查询某代次全部接收证据，按序号升序。
     */
    public List<TaskFragmentReceipt> findByTaskAndAttempt(long taskId, int attempt) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_fragment_receipt"
                + " WHERE task_id = ? AND attempt = ? ORDER BY seq_no", MAPPER, taskId, attempt);
    }

    /**
     * 查询任务全部代次的接收证据（诊断用），先按代次再按序号升序。
     */
    public List<TaskFragmentReceipt> findByTask(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_fragment_receipt"
                + " WHERE task_id = ? ORDER BY attempt, seq_no", MAPPER, taskId);
    }

    public int countByTaskAndAttempt(long taskId, int attempt) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_fragment_receipt WHERE task_id = ? AND attempt = ?",
                Integer.class, taskId, attempt);
        return count == null ? 0 : count;
    }
}
