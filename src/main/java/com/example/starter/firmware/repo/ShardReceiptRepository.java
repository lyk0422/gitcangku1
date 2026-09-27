package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ShardReceipt;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 分片接收证据数据访问。证据只增不改；uk_task_attempt_shard 唯一约束保证
 * 同任务同代次同序号并发提交最多一笔成功。
 */
@Repository
public class ShardReceiptRepository {

    private static final RowMapper<ShardReceipt> MAPPER = (rs, rowNum) -> new ShardReceipt(
            rs.getLong("id"), rs.getLong("task_id"), rs.getInt("attempt_no"),
            rs.getLong("release_id"), rs.getInt("release_version"),
            rs.getInt("shard_index"), rs.getString("expected_digest"),
            rs.getString("received_digest"), rs.getInt("digest_matched") == 1,
            rs.getString("received_at_utc"));

    private static final String COLUMNS = "id, task_id, attempt_no, release_id, release_version, shard_index,"
            + " expected_digest, received_digest, digest_matched, received_at_utc";

    private final JdbcTemplate jdbc;

    public ShardReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入接收证据。重复序号（含并发）抛 {@link DuplicateKeyException}，由服务层按提交顺序裁决。
     */
    public void insert(long taskId, int attemptNo, long releaseId, int releaseVersion, int shardIndex,
                       String expectedDigest, String receivedDigest, boolean matched, String receivedAtUtc) {
        jdbc.update("INSERT INTO task_shard_receipt (task_id, attempt_no, release_id, release_version,"
                        + " shard_index, expected_digest, received_digest, digest_matched, received_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                taskId, attemptNo, releaseId, releaseVersion, shardIndex, expectedDigest, receivedDigest,
                matched ? 1 : 0, receivedAtUtc);
    }

    public Optional<ShardReceipt> find(long taskId, int attemptNo, int shardIndex) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_shard_receipt"
                        + " WHERE task_id = ? AND attempt_no = ? AND shard_index = ?",
                MAPPER, taskId, attemptNo, shardIndex).stream().findFirst();
    }

    public List<ShardReceipt> findByTaskAndAttemptOrdered(long taskId, int attemptNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_shard_receipt"
                        + " WHERE task_id = ? AND attempt_no = ? ORDER BY shard_index",
                MAPPER, taskId, attemptNo);
    }

    public long countByTaskAndAttempt(long taskId, int attemptNo) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_shard_receipt WHERE task_id = ? AND attempt_no = ?",
                Long.class, taskId, attemptNo);
        return count == null ? 0 : count;
    }
}
