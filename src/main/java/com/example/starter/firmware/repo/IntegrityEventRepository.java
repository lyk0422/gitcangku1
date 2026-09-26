package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.IntegrityEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 完整性判定事件数据访问。事件只增不改，每次分片接收的判定（成功或失败）都固化一条。
 */
@Repository
public class IntegrityEventRepository {

    private static final RowMapper<IntegrityEvent> MAPPER = (rs, rowNum) -> new IntegrityEvent(
            rs.getLong("id"), rs.getLong("task_id"), rs.getInt("attempt_no"),
            rs.getLong("release_id"), rs.getInt("release_version"),
            rs.getString("result"), rs.getString("reason"),
            rs.getInt("required_shard_count"), rs.getInt("received_shard_count"),
            rs.getInt("missing_shard_count"), rs.getString("missing_shards"),
            rs.getString("duplicate_shards"), rs.getString("mismatched_shards"),
            rs.getString("expected_full_digest"), rs.getString("actual_full_digest"),
            rs.getString("decided_at_utc"));

    private static final String COLUMNS = "id, task_id, attempt_no, release_id, release_version, result, reason,"
            + " required_shard_count, received_shard_count, missing_shard_count, missing_shards,"
            + " duplicate_shards, mismatched_shards, expected_full_digest, actual_full_digest, decided_at_utc";

    private final JdbcTemplate jdbc;

    public IntegrityEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(IntegrityEvent event) {
        jdbc.update("INSERT INTO integrity_event"
                        + " (task_id, attempt_no, release_id, release_version, result, reason,"
                        + " required_shard_count, received_shard_count, missing_shard_count, missing_shards,"
                        + " duplicate_shards, mismatched_shards, expected_full_digest, actual_full_digest,"
                        + " decided_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                event.taskId(), event.attemptNo(), event.releaseId(), event.releaseVersion(),
                event.result(), event.reason(), event.requiredShardCount(), event.receivedShardCount(),
                event.missingShardCount(), event.missingShards(), event.duplicateShards(),
                event.mismatchedShards(), event.expectedFullDigest(), event.actualFullDigest(),
                event.decidedAtUtc());
    }

    public List<IntegrityEvent> findByTask(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM integrity_event WHERE task_id = ? ORDER BY id",
                MAPPER, taskId);
    }

    public List<IntegrityEvent> findByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM integrity_event WHERE release_id = ? ORDER BY id",
                MAPPER, releaseId);
    }
}
