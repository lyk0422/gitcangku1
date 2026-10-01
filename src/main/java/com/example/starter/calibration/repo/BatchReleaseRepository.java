package com.example.starter.calibration.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.BatchRelease;

/**
 * 批次放行快照持久化。快照只增不改，后续测量修订不得改写。
 */
@Repository
public class BatchReleaseRepository {

    private static final RowMapper<BatchRelease> MAPPER = (rs, rowNum) -> new BatchRelease(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getInt("revision"),
            rs.getInt("included_count"),
            rs.getInt("excluded_count"),
            rs.getBigDecimal("mean_value"),
            rs.getString("released_by"),
            JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public BatchReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条放行快照。
     */
    public void insert(BatchRelease release) {
        jdbc.update("INSERT INTO batch_release "
                        + "(batch_id, revision, included_count, excluded_count, mean_value, released_by, released_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                release.batchId(),
                release.revision(),
                release.includedCount(),
                release.excludedCount(),
                release.meanValue(),
                release.releasedBy(),
                JdbcTimes.toDb(release.releasedAt()));
    }

    /**
     * 查询批次全部放行快照（按生成顺序）。
     */
    public List<BatchRelease> findByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM batch_release WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }
}