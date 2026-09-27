package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.BatchReading;
import com.example.starter.calibration.model.BatchStatus;
import com.example.starter.calibration.model.MeasurementBatch;

/**
 * 测量批次与批次读数持久化。batch_key 全局唯一、(batch_id, reading_key) 唯一作为幂等键；
 * 批次内写操作（读数提交、复核、放行）通过批次行锁串行化。
 */
@Repository
public class OutlierBatchRepository {

    private static final RowMapper<MeasurementBatch> BATCH_MAPPER = (rs, rowNum) -> new MeasurementBatch(
            rs.getLong("id"),
            rs.getString("batch_key"),
            rs.getString("instrument_id"),
            rs.getLong("certificate_id"),
            rs.getBigDecimal("target_value"),
            rs.getBigDecimal("allowed_deviation"),
            rs.getInt("revision"),
            BatchStatus.valueOf(rs.getString("status")),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private static final RowMapper<BatchReading> READING_MAPPER = (rs, rowNum) -> new BatchReading(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getString("reading_key"),
            rs.getBigDecimal("reading_value"),
            rs.getString("submitted_by"),
            rs.getInt("batch_revision"),
            rs.getBoolean("excluded"),
            JdbcTimes.fromDb(rs.getObject("excluded_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public OutlierBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入批次并返回生成的 ID；batch_key 冲突时抛出 DuplicateKeyException。
     */
    public long insertBatch(MeasurementBatch batch) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement_batch "
                            + "(batch_key, instrument_id, certificate_id, target_value, allowed_deviation, "
                            + "revision, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batch.batchKey());
            ps.setString(2, batch.instrumentId());
            ps.setLong(3, batch.certificateId());
            ps.setBigDecimal(4, batch.targetValue());
            ps.setBigDecimal(5, batch.allowedDeviation());
            ps.setInt(6, batch.revision());
            ps.setString(7, batch.status().name());
            ps.setObject(8, JdbcTimes.toDb(batch.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按批次键查询（不加锁）。
     */
    public Optional<MeasurementBatch> findBatchByKey(String batchKey) {
        return jdbc.query("SELECT * FROM measurement_batch WHERE batch_key = ?", BATCH_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 按批次键查询并加行锁（须在事务内调用），串行化批次内全部写操作。
     */
    public Optional<MeasurementBatch> findBatchByKeyForUpdate(String batchKey) {
        return jdbc.query("SELECT * FROM measurement_batch WHERE batch_key = ? FOR UPDATE",
                BATCH_MAPPER, batchKey).stream().findFirst();
    }

    /**
     * 按 ID 查询批次（不加锁）。
     */
    public Optional<MeasurementBatch> findBatchById(long id) {
        return jdbc.query("SELECT * FROM measurement_batch WHERE id = ?", BATCH_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 加行锁（须在事务内调用），用于复核时按离群记录定位批次后串行化。
     */
    public Optional<MeasurementBatch> findBatchByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM measurement_batch WHERE id = ? FOR UPDATE",
                BATCH_MAPPER, id).stream().findFirst();
    }

    /**
     * 已放行批次新增读数：修订版本 +1 并回到 OPEN（须在持有批次行锁的事务内调用）。
     */
    public void bumpRevision(long batchId, int newRevision) {
        jdbc.update("UPDATE measurement_batch SET revision = ?, status = ? WHERE id = ?",
                newRevision, BatchStatus.OPEN.name(), batchId);
    }

    /**
     * 将批次置为已放行（须在持有批次行锁的事务内调用）。
     */
    public void markReleased(long batchId) {
        jdbc.update("UPDATE measurement_batch SET status = ? WHERE id = ?",
                BatchStatus.RELEASED.name(), batchId);
    }

    /**
     * 插入读数并返回生成的 ID；(batch_id, reading_key) 冲突时抛出 DuplicateKeyException。
     */
    public long insertReading(BatchReading reading) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO batch_reading "
                            + "(batch_id, reading_key, reading_value, submitted_by, batch_revision, "
                            + "excluded, created_at) VALUES (?, ?, ?, ?, ?, FALSE, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, reading.batchId());
            ps.setString(2, reading.readingKey());
            ps.setBigDecimal(3, reading.readingValue());
            ps.setString(4, reading.submittedBy());
            ps.setInt(5, reading.batchRevision());
            ps.setObject(6, JdbcTimes.toDb(reading.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 ID 查询读数（不加锁）。
     */
    public Optional<BatchReading> findReadingById(long id) {
        return jdbc.query("SELECT * FROM batch_reading WHERE id = ?", READING_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询批次全部读数（按 ID 升序，即提交顺序）。
     */
    public List<BatchReading> findReadingsByBatch(long batchId) {
        return jdbc.query("SELECT * FROM batch_reading WHERE batch_id = ? ORDER BY id",
                READING_MAPPER, batchId);
    }

    /**
     * 将读数置为已排除（须在持有批次行锁的事务内调用，与 REJECT 裁决同事务）。
     */
    public void markReadingExcluded(long readingId, Instant excludedAt) {
        jdbc.update("UPDATE batch_reading SET excluded = TRUE, excluded_at = ? WHERE id = ?",
                JdbcTimes.toDb(excludedAt), readingId);
    }
}
