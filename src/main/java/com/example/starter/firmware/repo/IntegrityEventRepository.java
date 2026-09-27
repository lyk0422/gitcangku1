package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.IntegrityEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 完整性事件流水数据访问，只增不改。诊断查询时间区间为 UTC、左闭右开 [from, to)。
 */
@Repository
public class IntegrityEventRepository {

    private static final RowMapper<IntegrityEvent> MAPPER = (rs, rowNum) -> new IntegrityEvent(
            rs.getLong("id"), rs.getLong("task_id"), rs.getInt("attempt"), rs.getString("event_type"),
            rs.getLong("release_id"), rs.getInt("release_version"),
            (Integer) rs.getObject("seq_no"),
            rs.getString("expected_digest"), rs.getString("received_digest"),
            rs.getString("aggregate_expected"), rs.getString("aggregate_actual"),
            rs.getString("reason"), rs.getString("created_at_utc"));

    private static final String COLUMNS = "id, task_id, attempt, event_type, release_id, release_version,"
            + " seq_no, expected_digest, received_digest, aggregate_expected, aggregate_actual, reason,"
            + " created_at_utc";

    private final JdbcTemplate jdbc;

    public IntegrityEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertReceived(long taskId, int attempt, long releaseId, int releaseVersion,
                               int seqNo, String expectedDigest, String receivedDigest, String createdAtUtc) {
        jdbc.update("INSERT INTO task_integrity_event"
                        + " (task_id, attempt, event_type, release_id, release_version, seq_no,"
                        + " expected_digest, received_digest, aggregate_expected, aggregate_actual,"
                        + " reason, created_at_utc)"
                        + " VALUES (?, ?, 'RECEIVED', ?, ?, ?, ?, ?, NULL, NULL, NULL, ?)",
                taskId, attempt, releaseId, releaseVersion, seqNo, expectedDigest, receivedDigest, createdAtUtc);
    }

    public void insertVerdict(long taskId, int attempt, String eventType, long releaseId, int releaseVersion,
                              String aggregateExpected, String aggregateActual, String reason,
                              String createdAtUtc) {
        jdbc.update("INSERT INTO task_integrity_event"
                        + " (task_id, attempt, event_type, release_id, release_version, seq_no,"
                        + " expected_digest, received_digest, aggregate_expected, aggregate_actual,"
                        + " reason, created_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?, ?)",
                taskId, attempt, eventType, releaseId, releaseVersion,
                aggregateExpected, aggregateActual, reason, createdAtUtc);
    }

    /**
     * 任务全部事件（所有代次），按事件发生顺序（id 升序）返回。
     */
    public List<IntegrityEvent> findByTask(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_integrity_event WHERE task_id = ? ORDER BY id",
                MAPPER, taskId);
    }

    /**
     * 指定代次事件，按 id 升序。
     */
    public List<IntegrityEvent> findByTaskAndAttempt(long taskId, int attempt) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_integrity_event"
                + " WHERE task_id = ? AND attempt = ? ORDER BY id", MAPPER, taskId, attempt);
    }

    /**
     * 发布单下全部事件，时间区间左闭右开 [fromUtc, toUtc)，任一为空表示不限制该侧。
     * created_at_utc 统一为秒精度 UTC ISO-8601（后缀 Z），字典序与时间序一致，直接字符串比较。
     */
    public List<IntegrityEvent> findByReleaseAndRange(long releaseId, String fromUtc, String toUtc) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM task_integrity_event"
                + " WHERE release_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(releaseId);
        if (fromUtc != null) {
            sql.append(" AND created_at_utc >= ?");
            args.add(fromUtc);
        }
        if (toUtc != null) {
            sql.append(" AND created_at_utc < ?");
            args.add(toUtc);
        }
        sql.append(" ORDER BY id");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }
}
