package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.BatchStatus;
import com.example.starter.calibration.model.CalibrationBatch;

/**
 * 测量批次持久化。batch_key 全局唯一；批次级操作通过行锁（FOR UPDATE）按提交顺序串行化。
 */
@Repository
public class BatchRepository {

    private static final RowMapper<CalibrationBatch> MAPPER = (rs, rowNum) -> new CalibrationBatch(
            rs.getLong("id"),
            rs.getString("batch_key"),
            rs.getString("instrument_id"),
            rs.getBigDecimal("target_value"),
            rs.getBigDecimal("allowed_deviation"),
            rs.getInt("revision"),
            BatchStatus.valueOf(rs.getString("status")),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入批次并返回生成的 ID；batch_key 冲突时抛出 DuplicateKeyException。
     */
    public long insert(CalibrationBatch batch) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO calibration_batch "
                            + "(batch_key, instrument_id, target_value, allowed_deviation, revision, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batch.batchKey());
            ps.setString(2, batch.instrumentId());
            ps.setBigDecimal(3, batch.targetValue());
            ps.setBigDecimal(4, batch.allowedDeviation());
            ps.setInt(5, batch.revision());
            ps.setString(6, batch.status().name());
            ps.setObject(7, JdbcTimes.toDb(batch.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按批次键查询（不加锁）。
     */
    public Optional<CalibrationBatch> findByKey(String batchKey) {
        return jdbc.query("SELECT * FROM calibration_batch WHERE batch_key = ?", MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 按批次键查询并加行锁（须在事务内调用），用于读数提交、复核、修订与放行的串行化。
     */
    public Optional<CalibrationBatch> findByKeyForUpdate(String batchKey) {
        return jdbc.query("SELECT * FROM calibration_batch WHERE batch_key = ? FOR UPDATE", MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行锁（须在事务内调用）。
     */
    public Optional<CalibrationBatch> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM calibration_batch WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 推进测量修订版本并将状态置为 OPEN（已放行批次新增读数时调用）。
     */
    public void updateRevisionAndStatus(long id, int revision, BatchStatus status) {
        jdbc.update("UPDATE calibration_batch SET revision = ?, status = ? WHERE id = ?",
                revision, status.name(), id);
    }

    /**
     * 更新批次状态（须在持有行锁的事务内调用）。
     */
    public void updateStatus(long id, BatchStatus status) {
        jdbc.update("UPDATE calibration_batch SET status = ? WHERE id = ?", status.name(), id);
    }
}