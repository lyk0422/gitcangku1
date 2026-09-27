package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.BatchStatus;
import com.example.starter.calibration.model.MeasurementBatch;

/**
 * 测量批次持久化。batch_key 全局唯一作为幂等键；
 * 读数提交、复核、修订与放行通过批次行锁串行化，按事务提交顺序裁决。
 */
@Repository
public class BatchRepository {

    private static final RowMapper<MeasurementBatch> MAPPER = (rs, rowNum) -> new MeasurementBatch(
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
    public long insert(MeasurementBatch batch) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement_batch "
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
    public Optional<MeasurementBatch> findByKey(String batchKey) {
        return jdbc.query("SELECT * FROM measurement_batch WHERE batch_key = ?", MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 按批次键查询并加行锁（须在事务内调用），用于串行化读数提交、复核、修订与放行。
     */
    public Optional<MeasurementBatch> findByKeyForUpdate(String batchKey) {
        return jdbc.query("SELECT * FROM measurement_batch WHERE batch_key = ? FOR UPDATE", MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行锁（须在事务内调用），用于复核时串行化同批次的并发操作。
     */
    public Optional<MeasurementBatch> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM measurement_batch WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 应用测量修订：目标值与允许偏差更新、修订版本号 +1、状态回到 OPEN（须在持有行锁的事务内调用）。
     */
    public void applyRevision(long id, java.math.BigDecimal targetValue,
                              java.math.BigDecimal allowedDeviation, int newRevision) {
        jdbc.update("UPDATE measurement_batch SET target_value = ?, allowed_deviation = ?, "
                        + "revision = ?, status = ? WHERE id = ?",
                targetValue, allowedDeviation, newRevision, BatchStatus.OPEN.name(), id);
    }

    /**
     * 将批次当前修订置为已放行（须在持有行锁的事务内调用）。
     */
    public void markReleased(long id) {
        jdbc.update("UPDATE measurement_batch SET status = ? WHERE id = ?",
                BatchStatus.RELEASED.name(), id);
    }

    /**
     * 供测试与诊断使用：按 ID 查询（不加锁）。
     */
    public Optional<MeasurementBatch> findById(long id) {
        return jdbc.query("SELECT * FROM measurement_batch WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }
}
