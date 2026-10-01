package com.example.starter.calibration.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.BatchEvent;
import com.example.starter.calibration.model.BatchEventType;
import com.example.starter.calibration.model.ReviewDecision;

/**
 * 批次事件持久化。事件不可变追加，按 ID 升序即提交顺序。
 */
@Repository
public class BatchEventRepository {

    private static final RowMapper<BatchEvent> MAPPER = (rs, rowNum) -> new BatchEvent(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            BatchEventType.valueOf(rs.getString("event_type")),
            (Long) rs.getObject("outlier_id"),
            (Long) rs.getObject("reading_id"),
            rs.getString("actor"),
            (Integer) rs.getObject("revision"),
            rs.getBigDecimal("target_value"),
            rs.getBigDecimal("tolerance"),
            rs.getBigDecimal("reading_value"),
            rs.getBigDecimal("computed_value"),
            rs.getString("decision") == null ? null : ReviewDecision.valueOf(rs.getString("decision")),
            rs.getString("reason"),
            rs.getString("details"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public BatchEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条批次事件。
     */
    public void insert(BatchEvent event) {
        jdbc.update("INSERT INTO batch_event "
                        + "(batch_id, event_type, outlier_id, reading_id, actor, revision, target_value, "
                        + "tolerance, reading_value, computed_value, decision, reason, details, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                event.batchId(),
                event.eventType().name(),
                event.outlierId(),
                event.readingId(),
                event.actor(),
                event.revision(),
                event.targetValue(),
                event.tolerance(),
                event.readingValue(),
                event.computedValue(),
                event.decision() == null ? null : event.decision().name(),
                event.reason(),
                event.details(),
                JdbcTimes.toDb(event.createdAt()));
    }

    /**
     * 查询批次全部事件（按提交顺序）。
     */
    public List<BatchEvent> findByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM batch_event WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }
}