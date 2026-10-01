package com.example.starter.calibration.repo;

import java.math.BigDecimal;
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
import com.example.starter.calibration.model.MeasurementBatch;

/**
 * 测量批次持久化。batch_key 全局唯一作为幂等键；
 * 读数提交、复核、修订与放行通过对批次行 FOR UPDATE 串行化，按事务提交顺序生效。
 */
@Repository
public class BatchRepository {

    private static final RowMapper<MeasurementBatch> MAPPER = (rs, rowNum) -> new MeasurementBatch(
            rs.getLong("id"),
            rs.getString("batch_key"),
            rs.getString("instrument_id"),
            rs.getBigDecimal("target_value"),
            rs.getBigDecimal("tolerance"),
            rs.getInt("revision"),
            BatchStatus.valueOf(rs.getString("status")),
            rs.getInt("reading_count"),
            rs.getInt("included_count"),
            rs.getInt("excluded_count"),
            rs.getBigDecimal("mean_value"),
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
                            + "(batch_key, instrument_id, target_value, tolerance, revision, status, "
                            + "reading_count, included_count, excluded_count, mean_value, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batch.batchKey());
            ps.setString(2, batch.instrumentId());
            ps.setBigDecimal(3, batch.targetValue());
            ps.setBigDecimal(4, batch.tolerance());
            ps.setInt(5, batch.revision());
            ps.setString(6, batch.status().name());
            ps.setInt(7, batch.readingCount());
            ps.setInt(8, batch.includedCount());
            ps.setInt(9, batch.excludedCount());
            ps.setBigDecimal(10, batch.meanValue());
            ps.setObject(11, JdbcTimes.toDb(batch.createdAt()));
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
     * 按批次键查询并加行锁（须在事务内调用），用于串行化批次上的所有写操作。
     */
    public Optional<MeasurementBatch> findByKeyForUpdate(String batchKey) {
        return jdbc.query("SELECT * FROM measurement_batch WHERE batch_key = ? FOR UPDATE", MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 重算后回写批次统计（须在持有批次行锁的事务内调用）。meanValue 为 null 表示无未排除读数。
     */
    public void updateStats(long id, int readingCount, int includedCount, int excludedCount,
                            BigDecimal meanValue) {
        jdbc.update("UPDATE measurement_batch SET reading_count = ?, included_count = ?, "
                        + "excluded_count = ?, mean_value = ? WHERE id = ?",
                readingCount, includedCount, excludedCount, meanValue, id);
    }

    /**
     * 创建后续修订：修订版本递增并重新开放批次（须在持有批次行锁的事务内调用）。
     */
    public void openRevision(long id, int newRevision) {
        jdbc.update("UPDATE measurement_batch SET revision = ?, status = ? WHERE id = ?",
                newRevision, BatchStatus.OPEN.name(), id);
    }

    /**
     * 将批次置为已放行（须在持有批次行锁的事务内调用）。
     */
    public void markReleased(long id) {
        jdbc.update("UPDATE measurement_batch SET status = ? WHERE id = ?",
                BatchStatus.RELEASED.name(), id);
    }
}