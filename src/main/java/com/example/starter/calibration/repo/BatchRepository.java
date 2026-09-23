package com.example.starter.calibration.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.BatchStatus;
import com.example.starter.calibration.model.ReleaseBatch;

/**
 * 放行批次持久化。批次一经创建内容不可变；复核驳回仅原子更新状态与 reviewed_at。
 */
@Repository
public class BatchRepository {

    private static final RowMapper<ReleaseBatch> MAPPER = (rs, rowNum) -> new ReleaseBatch(
            rs.getString("batch_id"),
            rs.getString("released_by"),
            BatchStatus.valueOf(rs.getString("status")),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("reviewed_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建放行批次。
     */
    public void insert(ReleaseBatch batch) {
        jdbc.update("INSERT INTO release_batch (batch_id, released_by, status, created_at, reviewed_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                batch.batchId(), batch.releasedBy(), batch.status().name(),
                JdbcTimes.toDb(batch.createdAt()),
                batch.reviewedAt() == null ? null : Timestamp.from(batch.reviewedAt()));
    }

    /**
     * 按批次 ID 查询（不加锁）。
     */
    public Optional<ReleaseBatch> findById(String batchId) {
        return jdbc.query("SELECT * FROM release_batch WHERE batch_id = ?", MAPPER, batchId)
                .stream().findFirst();
    }

    /**
     * 按批次 ID 查询并加行锁（须在事务内调用），串行化复核与重新放行。
     */
    public Optional<ReleaseBatch> findByIdForUpdate(String batchId) {
        return jdbc.query("SELECT * FROM release_batch WHERE batch_id = ? FOR UPDATE", MAPPER, batchId)
                .stream().findFirst();
    }

    /**
     * 将批次置为复核驳回并冻结（须在持有行锁的事务内调用）。
     */
    public void markReviewRequired(String batchId, Instant reviewedAt) {
        jdbc.update("UPDATE release_batch SET status = ?, reviewed_at = ? WHERE batch_id = ?",
                BatchStatus.REVIEW_REQUIRED.name(), JdbcTimes.toDb(reviewedAt), batchId);
    }
}
