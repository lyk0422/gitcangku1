package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.IntegrityEvent;
import com.example.starter.firmware.domain.IntegrityEventType;
import com.example.starter.firmware.domain.IntegrityFailureReason;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 完整性事件数据访问。事件固化判定证据，只增不改；查询按 id 升序保证稳定排序。
 */
@Repository
public class IntegrityEventRepository {

    private static final RowMapper<IntegrityEvent> MAPPER = (rs, rowNum) -> {
        String reason = rs.getString("reason");
        return new IntegrityEvent(rs.getLong("id"), rs.getLong("task_id"), rs.getInt("attempt_no"),
                rs.getLong("release_id"), IntegrityEventType.valueOf(rs.getString("event_type")),
                rs.getObject("chunk_index") == null ? null : rs.getInt("chunk_index"),
                rs.getString("expected_digest"), rs.getString("received_digest"),
                rs.getObject("chunk_count") == null ? null : rs.getInt("chunk_count"),
                rs.getString("aggregate_digest"), rs.getString("package_digest"),
                reason == null ? null : IntegrityFailureReason.valueOf(reason),
                rs.getString("detail"), rs.getString("occurred_at_utc"));
    };

    private static final String COLUMNS = "id, task_id, attempt_no, release_id, event_type, chunk_index,"
            + " expected_digest, received_digest, chunk_count, aggregate_digest, package_digest,"
            + " reason, detail, occurred_at_utc";

    private final JdbcTemplate jdbc;

    public IntegrityEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(IntegrityEvent event) {
        jdbc.update("INSERT INTO integrity_event (task_id, attempt_no, release_id, event_type, chunk_index,"
                        + " expected_digest, received_digest, chunk_count, aggregate_digest, package_digest,"
                        + " reason, detail, occurred_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                event.taskId(), event.attemptNo(), event.releaseId(), event.eventType().name(),
                event.chunkIndex(), event.expectedDigest(), event.receivedDigest(), event.chunkCount(),
                event.aggregateDigest(), event.packageDigest(),
                event.reason() == null ? null : event.reason().name(),
                event.detail(), event.occurredAtUtc());
    }

    /**
     * 任务全部完整性事件，按 id 升序（即发生顺序）。时间区间过滤由调用方按左闭右开解释，
     * 避免依赖字符串字典序。
     */
    public List<IntegrityEvent> findByTask(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM integrity_event WHERE task_id = ? ORDER BY id",
                MAPPER, taskId);
    }

    /**
     * 指定尝试代次的完整性事件，按 id 升序。
     */
    public List<IntegrityEvent> findByTaskAndAttempt(long taskId, int attemptNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM integrity_event"
                        + " WHERE task_id = ? AND attempt_no = ? ORDER BY id",
                MAPPER, taskId, attemptNo);
    }
}
