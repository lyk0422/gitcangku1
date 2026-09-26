package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ShardReceipt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 分片接收证据数据访问。证据只增不改；唯一约束 uk_receipt_task_attempt_shard
 * 保证同任务同代次同序号并发提交至多一笔成功。
 */
@Repository
public class ShardReceiptRepository {

    private static final RowMapper<ShardReceipt> MAPPER = (rs, rowNum) -> new ShardReceipt(
            rs.getLong("id"), rs.getLong("task_id"), rs.getInt("attempt_no"),
            rs.getLong("release_id"), rs.getInt("release_version"),
            rs.getInt("shard_no"), rs.getString("shard_digest"),
            rs.getString("received_at_utc"), rs.getString("request_id"));

    private static final String COLUMNS = "id, task_id, attempt_no, release_id, release_version,"
            + " shard_no, shard_digest, received_at_utc, request_id";

    private final JdbcTemplate jdbc;

    public ShardReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long taskId, int attemptNo, long releaseId, int releaseVersion,
                       int shardNo, String shardDigest, String receivedAtUtc, String requestId) {
        jdbc.update("INSERT INTO shard_receipt"
                        + " (task_id, attempt_no, release_id, release_version, shard_no, shard_digest,"
                        + " received_at_utc, request_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                taskId, attemptNo, releaseId, releaseVersion, shardNo, shardDigest, receivedAtUtc, requestId);
    }

    /**
     * 当前代次已接收分片，按序号升序。
     */
    public List<ShardReceipt> findByTaskAndAttempt(long taskId, int attemptNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM shard_receipt"
                        + " WHERE task_id = ? AND attempt_no = ? ORDER BY shard_no",
                MAPPER, taskId, attemptNo);
    }

    /**
     * 任务全部代次的接收证据，按代次、序号升序；历史代次证据保留可查。
     */
    public List<ShardReceipt> findByTask(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM shard_receipt"
                        + " WHERE task_id = ? ORDER BY attempt_no, shard_no", MAPPER, taskId);
    }

    /**
     * 发布单全部接收证据（诊断查询），按任务、代次、序号升序。
     */
    public List<ShardReceipt> findByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM shard_receipt"
                        + " WHERE release_id = ? ORDER BY task_id, attempt_no, shard_no", MAPPER, releaseId);
    }
}
