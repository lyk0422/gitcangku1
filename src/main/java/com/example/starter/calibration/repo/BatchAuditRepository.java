package com.example.starter.calibration.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.BatchReleaseSnapshot;
import com.example.starter.calibration.model.BatchStats;

/**
 * 批次统计重算历史与放行快照持久化。两类记录均为不可变审计证据，只增不改。
 */
@Repository
public class BatchAuditRepository {

    private static final RowMapper<BatchStats> STATS_MAPPER = (rs, rowNum) -> new BatchStats(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getInt("revision"),
            rs.getString("reason"),
            rs.getInt("reading_count"),
            rs.getInt("included_count"),
            rs.getInt("excluded_count"),
            rs.getBigDecimal("mean_value"),
            rs.getBigDecimal("min_value"),
            rs.getBigDecimal("max_value"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private static final RowMapper<BatchReleaseSnapshot> SNAPSHOT_MAPPER = (rs, rowNum) -> new BatchReleaseSnapshot(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getInt("revision"),
            rs.getLong("certificate_id"),
            rs.getString("released_by"),
            rs.getInt("included_count"),
            rs.getBigDecimal("mean_value"),
            JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public BatchAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条统计重算记录。
     */
    public void insertStats(BatchStats stats) {
        jdbc.update("INSERT INTO batch_stats_history "
                        + "(batch_id, revision, reason, reading_count, included_count, excluded_count, "
                        + "mean_value, min_value, max_value, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                stats.batchId(), stats.revision(), stats.reason(),
                stats.readingCount(), stats.includedCount(), stats.excludedCount(),
                stats.meanValue(), stats.minValue(), stats.maxValue(),
                JdbcTimes.toDb(stats.createdAt()));
    }

    /**
     * 查询批次统计重算历史（按 ID 升序，即重算顺序）。
     */
    public List<BatchStats> findStatsByBatch(long batchId) {
        return jdbc.query("SELECT * FROM batch_stats_history WHERE batch_id = ? ORDER BY id",
                STATS_MAPPER, batchId);
    }

    /**
     * 追加一条放行快照。
     */
    public void insertSnapshot(BatchReleaseSnapshot snapshot) {
        jdbc.update("INSERT INTO batch_release_snapshot "
                        + "(batch_id, revision, certificate_id, released_by, included_count, mean_value, released_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                snapshot.batchId(), snapshot.revision(), snapshot.certificateId(), snapshot.releasedBy(),
                snapshot.includedCount(), snapshot.meanValue(), JdbcTimes.toDb(snapshot.releasedAt()));
    }

    /**
     * 查询批次放行快照（按 ID 升序，即放行顺序）。
     */
    public List<BatchReleaseSnapshot> findSnapshotsByBatch(long batchId) {
        return jdbc.query("SELECT * FROM batch_release_snapshot WHERE batch_id = ? ORDER BY id",
                SNAPSHOT_MAPPER, batchId);
    }
}
