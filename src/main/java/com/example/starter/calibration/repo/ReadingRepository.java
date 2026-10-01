package com.example.starter.calibration.repo;

import java.math.BigDecimal;
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

/**
 * 批次读数持久化。reading_key 全局唯一作为幂等键；排除仅标记不删除。
 */
@Repository
public class ReadingRepository {

    /**
     * 批次统计快照。meanValue 为 null 表示无未排除读数。
     *
     * @param totalCount    读数总数（含已排除）
     * @param includedCount 未排除读数数量
     * @param meanValue     未排除读数计算值均值；无未排除读数时为 null
     */
    public record ReadingStats(int totalCount, int includedCount, BigDecimal meanValue) {
    }

    private static final RowMapper<BatchReading> MAPPER = (rs, rowNum) -> new BatchReading(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getString("reading_key"),
            JdbcTimes.fromDb(rs.getObject("measured_at", LocalDateTime.class)),
            rs.getBigDecimal("reading_value"),
            rs.getBigDecimal("computed_value"),
            rs.getString("submitted_by"),
            rs.getLong("certificate_id"),
            rs.getInt("measurement_revision"),
            rs.getBoolean("excluded"),
            JdbcTimes.fromDb(rs.getObject("excluded_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入读数并返回生成的 ID；reading_key 冲突时抛出 DuplicateKeyException。
     */
    public long insert(BatchReading reading) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO batch_reading "
                            + "(batch_id, reading_key, measured_at, reading_value, computed_value, "
                            + "submitted_by, certificate_id, measurement_revision, excluded, excluded_at, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, reading.batchId());
            ps.setString(2, reading.readingKey());
            ps.setObject(3, JdbcTimes.toDb(reading.measuredAt()));
            ps.setBigDecimal(4, reading.readingValue());
            ps.setBigDecimal(5, reading.computedValue());
            ps.setString(6, reading.submittedBy());
            ps.setLong(7, reading.certificateId());
            ps.setInt(8, reading.measurementRevision());
            ps.setBoolean(9, reading.excluded());
            ps.setObject(10, JdbcTimes.toDb(reading.excludedAt()));
            ps.setObject(11, JdbcTimes.toDb(reading.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 ID 查询（不加锁）。
     */
    public Optional<BatchReading> findById(long id) {
        return jdbc.query("SELECT * FROM batch_reading WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询批次全部读数（按 ID 升序）。
     */
    public List<BatchReading> findByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM batch_reading WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }

    /**
     * 将读数标记为已排除（须在持有批次行锁的事务内调用）。
     */
    public void markExcluded(long id, Instant excludedAt) {
        jdbc.update("UPDATE batch_reading SET excluded = TRUE, excluded_at = ? WHERE id = ?",
                JdbcTimes.toDb(excludedAt), id);
    }

    /**
     * 基于当前读数表重算批次统计（须在持有批次行锁的事务内调用，保证与写一致）。
     */
    public ReadingStats computeStats(long batchId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) AS total_count, "
                        + "COALESCE(SUM(CASE WHEN excluded = FALSE THEN 1 ELSE 0 END), 0) AS included_count, "
                        + "AVG(CASE WHEN excluded = FALSE THEN computed_value END) AS mean_value "
                        + "FROM batch_reading WHERE batch_id = ?",
                (rs, rowNum) -> new ReadingStats(
                        rs.getInt("total_count"),
                        rs.getInt("included_count"),
                        rs.getBigDecimal("mean_value")),
                batchId);
    }

    /**
     * 统计批次内未排除且证书已撤销的读数数量，用于放行前校验。
     */
    public int countIncludedWithRevokedCertificate(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_reading r "
                        + "JOIN calibration_certificate c ON c.id = r.certificate_id "
                        + "WHERE r.batch_id = ? AND r.excluded = FALSE AND c.revoked = TRUE",
                Integer.class, batchId);
        return count == null ? 0 : count;
    }
}