package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.ReleaseBatch;

/**
 * 放行批次头持久化。仅成功放行的批次写入记录；失败的批次整批回滚，不留状态。
 */
@Repository
public class ReleaseBatchRepository {

    private static final RowMapper<ReleaseBatch> MAPPER = (rs, rowNum) -> new ReleaseBatch(
            rs.getString("batch_id"),
            rs.getString("released_by"),
            JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)),
            rs.getInt("item_count"));

    private final JdbcTemplate jdbc;

    public ReleaseBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入放行批次头。
     */
    public void insert(String batchId, String releasedBy, Instant releasedAt, int itemCount) {
        jdbc.update("INSERT INTO release_batch (batch_id, released_by, released_at, item_count) "
                        + "VALUES (?, ?, ?, ?)",
                batchId, releasedBy, JdbcTimes.toDb(releasedAt), itemCount);
    }

    /**
     * 按批次 ID 查询批次头，用于放行诊断。
     */
    public Optional<ReleaseBatch> findById(String batchId) {
        return jdbc.query("SELECT * FROM release_batch WHERE batch_id = ?", MAPPER, batchId)
                .stream().findFirst();
    }
}
