package com.example.starter.calibration.repo;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.BatchStatus;
import com.example.starter.calibration.model.ReleaseBatch;

/**
 * 放行批次持久化。批次状态流转（RELEASED → REVIEW_REQUIRED → SUPERSEDED）由复核/重新放行事务
 * 在行锁内完成；request_id 唯一作为重新放行幂等键。
 */
@Repository
public class BatchRepository {

    private static final RowMapper<ReleaseBatch> MAPPER = (rs, rowNum) -> new ReleaseBatch(
            rs.getString("batch_id"),
            rs.getString("released_by"),
            JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)),
            BatchStatus.valueOf(rs.getString("status")),
            rs.getString("request_id"),
            rs.getString("request_fingerprint"),
            rs.getString("source_batch_id"));

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入放行批次；request_id 冲突时抛出 DuplicateKeyException。
     */
    public void insert(ReleaseBatch batch) {
        jdbc.update("INSERT INTO release_batch "
                        + "(batch_id, released_by, released_at, status, request_id, request_fingerprint, "
                        + "source_batch_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                batch.batchId(), batch.releasedBy(), JdbcTimes.toDb(batch.releasedAt()),
                batch.status().name(), batch.requestId(), batch.requestFingerprint(), batch.sourceBatchId());
    }

    /**
     * 按批次 ID 查询（不加锁）。
     */
    public Optional<ReleaseBatch> findById(String batchId) {
        return jdbc.query("SELECT * FROM release_batch WHERE batch_id = ?", MAPPER, batchId)
                .stream().findFirst();
    }

    /**
     * 按批次 ID 查询并加行锁（须在事务内调用），用于复核与重新放行的串行化。
     */
    public Optional<ReleaseBatch> findByIdForUpdate(String batchId) {
        return jdbc.query("SELECT * FROM release_batch WHERE batch_id = ? FOR UPDATE", MAPPER, batchId)
                .stream().findFirst();
    }

    /**
     * 按重新放行幂等键查询（不加锁）。
     */
    public Optional<ReleaseBatch> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM release_batch WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 查询以某批次为来源的重新放行批次 ID（后继批次）；不存在时为 empty。
     */
    public Optional<String> findSuccessorBatchId(String sourceBatchId) {
        return jdbc.query("SELECT batch_id FROM release_batch WHERE source_batch_id = ?",
                        (rs, rowNum) -> rs.getString("batch_id"), sourceBatchId)
                .stream().findFirst();
    }

    /**
     * 更新批次状态（须在持有行锁的事务内调用）。
     */
    public void updateStatus(String batchId, BatchStatus status) {
        jdbc.update("UPDATE release_batch SET status = ? WHERE batch_id = ?", status.name(), batchId);
    }
}
