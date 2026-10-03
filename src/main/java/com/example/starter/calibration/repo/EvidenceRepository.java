package com.example.starter.calibration.repo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.AuditEventType;
import com.example.starter.calibration.model.BatchReleaseSnapshot;
import com.example.starter.calibration.model.BatchStatsRecord;
import com.example.starter.calibration.model.OutlierAudit;
import com.example.starter.calibration.model.StatsTrigger;

/**
 * 证据类持久化：离群审计、统计重算记录、放行快照。全部只增不改（不可变），按 ID 升序读取。
 */
@Repository
public class EvidenceRepository {

    private static final RowMapper<OutlierAudit> AUDIT_MAPPER = (rs, rowNum) -> new OutlierAudit(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getObject("outlier_id", Long.class),
            AuditEventType.valueOf(rs.getString("event_type")),
            rs.getInt("batch_revision"),
            rs.getBigDecimal("target_value"),
            rs.getBigDecimal("allowed_deviation"),
            rs.getBigDecimal("reading_value"),
            rs.getString("actor"),
            rs.getString("decision"),
            rs.getString("detail"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private static final RowMapper<BatchStatsRecord> STATS_MAPPER = (rs, rowNum) -> new BatchStatsRecord(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getInt("batch_revision"),
            rs.getInt("included_count"),
            rs.getInt("excluded_count"),
            rs.getBigDecimal("mean_value"),
            rs.getBigDecimal("min_value"),
            rs.getBigDecimal("max_value"),
            StatsTrigger.valueOf(rs.getString("trigger_event")),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private static final RowMapper<BatchReleaseSnapshot> SNAPSHOT_MAPPER = (rs, rowNum) -> new BatchReleaseSnapshot(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getInt("batch_revision"),
            rs.getInt("included_count"),
            rs.getInt("excluded_count"),
            rs.getBigDecimal("mean_value"),
            rs.getBigDecimal("min_value"),
            rs.getBigDecimal("max_value"),
            rs.getString("released_by"),
            JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public EvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条离群审计记录。
     */
    public void insertAudit(OutlierAudit audit) {
        jdbc.update("INSERT INTO outlier_audit "
                        + "(batch_id, outlier_id, event_type, batch_revision, target_value, allowed_deviation, "
                        + "reading_value, actor, decision, detail, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                audit.batchId(), audit.outlierId(), audit.eventType().name(), audit.batchRevision(),
                audit.targetValue(), audit.allowedDeviation(), audit.readingValue(),
                audit.actor(), audit.decision(), audit.detail(), JdbcTimes.toDb(audit.createdAt()));
    }

    /**
     * 查询批次全部审计记录（按 ID 升序）。
     */
    public List<OutlierAudit> findAuditsByBatch(long batchId) {
        return jdbc.query("SELECT * FROM outlier_audit WHERE batch_id = ? ORDER BY id", AUDIT_MAPPER, batchId);
    }

    /**
     * 追加一条统计重算记录。
     */
    public void insertStats(BatchStatsRecord stats) {
        jdbc.update("INSERT INTO batch_stats_record "
                        + "(batch_id, batch_revision, included_count, excluded_count, mean_value, min_value, "
                        + "max_value, trigger_event, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                stats.batchId(), stats.batchRevision(), stats.includedCount(), stats.excludedCount(),
                stats.meanValue(), stats.minValue(), stats.maxValue(), stats.trigger().name(),
                JdbcTimes.toDb(stats.createdAt()));
    }

    /**
     * 查询批次全部统计记录（按 ID 升序）。
     */
    public List<BatchStatsRecord> findStatsByBatch(long batchId) {
        return jdbc.query("SELECT * FROM batch_stats_record WHERE batch_id = ? ORDER BY id", STATS_MAPPER, batchId);
    }

    /**
     * 查询批次最新一条统计记录。
     */
    public Optional<BatchStatsRecord> findLatestStats(long batchId) {
        return jdbc.query("SELECT * FROM batch_stats_record WHERE batch_id = ? ORDER BY id DESC LIMIT 1",
                STATS_MAPPER, batchId).stream().findFirst();
    }

    /**
     * 追加一条放行快照。
     */
    public void insertSnapshot(BatchReleaseSnapshot snapshot) {
        jdbc.update("INSERT INTO batch_release_snapshot "
                        + "(batch_id, batch_revision, included_count, excluded_count, mean_value, min_value, "
                        + "max_value, released_by, released_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snapshot.batchId(), snapshot.batchRevision(), snapshot.includedCount(), snapshot.excludedCount(),
                snapshot.meanValue(), snapshot.minValue(), snapshot.maxValue(),
                snapshot.releasedBy(), JdbcTimes.toDb(snapshot.releasedAt()));
    }

    /**
     * 查询批次全部放行快照（按 ID 升序）。
     */
    public List<BatchReleaseSnapshot> findSnapshotsByBatch(long batchId) {
        return jdbc.query("SELECT * FROM batch_release_snapshot WHERE batch_id = ? ORDER BY id",
                SNAPSHOT_MAPPER, batchId);
    }
}