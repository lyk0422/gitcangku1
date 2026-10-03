package com.example.starter.calibration.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.BatchReleaseSnapshot;
import com.example.starter.calibration.model.BatchStatsRecord;

/**
 * 批次统计重算记录与放行快照持久化。两类记录均不可变，只增不改。
 */
@Repository
public class StatsRepository {

    private static final RowMapper<BatchStatsRecord> STATS_MAPPER = (rs, rowNum) -> new BatchStatsRecord(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getInt("revision"),
            rs.getString("trigger_action"),
            rs.getInt("reading_count"),
            rs.getInt("excluded_count"),
            rs.getBigDecimal("mean_value"),
            rs.getBigDecimal("min_value"),
            rs.getBigDecimal("max_value"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private static final RowMapper<BatchReleaseSnapshot> SNAPSHOT_MAPPER = (rs, rowNum) -> new BatchReleaseSnapshot(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getInt("revision"),
            rs.getInt("reading_count"),
            rs.getInt("excluded_count"),
            rs.getBigDecimal("mean_value"),
            rs.getString("released_by"),
            JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public StatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条统计重算记录。
     */
    public void insertStats(BatchStatsRecord record) {
        jdbc.update("INSERT INTO batch_stats_record "
                        + "(batch_id, revision, trigger_action, reading_count, excluded_count, "
                        + "mean_value, min_value, max_value, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.batchId(), record.revision(), record.triggerAction(), record.readingCount(),
                record.excludedCount(), record.meanValue(), record.minValue(), record.maxValue(),
                JdbcTimes.toDb(record.createdAt()));
    }

    /**
     * 查询批次全部统计重算记录（按 ID 升序）。
     */
    public List<BatchStatsRecord> findStatsByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM batch_stats_record WHERE batch_id = ? ORDER BY id",
                STATS_MAPPER, batchId);
    }

    /**
     * 追加一条放行快照。
     */
    public void insertSnapshot(BatchReleaseSnapshot snapshot) {
        jdbc.update("INSERT INTO batch_release_snapshot "
                        + "(batch_id, revision, reading_count, excluded_count, mean_value, released_by, released_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                snapshot.batchId(), snapshot.revision(), snapshot.readingCount(), snapshot.excludedCount(),
                snapshot.meanValue(), snapshot.releasedBy(), JdbcTimes.toDb(snapshot.releasedAt()));
    }

    /**
     * 查询批次全部放行快照（按 ID 升序）。
     */
    public List<BatchReleaseSnapshot> findSnapshotsByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM batch_release_snapshot WHERE batch_id = ? ORDER BY id",
                SNAPSHOT_MAPPER, batchId);
    }

}