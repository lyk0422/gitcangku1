package com.example.starter.calibration.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.BatchStatsRecord;

/**
 * 批次统计重算记录持久化。记录只增不改，封存每次重算时的统计结果。
 */
@Repository
public class StatsRepository {

    private static final RowMapper<BatchStatsRecord> MAPPER = (rs, rowNum) -> new BatchStatsRecord(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getInt("batch_revision"),
            rs.getString("trigger_type"),
            rs.getInt("included_count"),
            rs.getInt("excluded_count"),
            rs.getBigDecimal("mean_value"),
            rs.getBigDecimal("min_value"),
            rs.getBigDecimal("max_value"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public StatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条统计重算记录。
     */
    public void insert(BatchStatsRecord record) {
        jdbc.update("INSERT INTO batch_stats_record "
                        + "(batch_id, batch_revision, trigger_type, included_count, excluded_count, "
                        + "mean_value, min_value, max_value, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.batchId(), record.batchRevision(), record.triggerType(),
                record.includedCount(), record.excludedCount(),
                record.meanValue(), record.minValue(), record.maxValue(),
                JdbcTimes.toDb(record.createdAt()));
    }

    /**
     * 查询批次全部统计重算记录（按 ID 升序，即重算顺序）。
     */
    public List<BatchStatsRecord> findByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM batch_stats_record WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }
}
