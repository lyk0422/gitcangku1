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

/**
 * 批次读数持久化。reading_key 全局唯一；读数只增不改，仅 REJECT 裁决时置排除标记。
 */
@Repository
public class ReadingRepository {

    private static final RowMapper<BatchReading> MAPPER = (rs, rowNum) -> new BatchReading(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getString("reading_key"),
            rs.getInt("batch_revision"),
            JdbcTimes.fromDb(rs.getObject("measured_at", LocalDateTime.class)),
            rs.getBigDecimal("reading_value"),
            rs.getString("submitted_by"),
            rs.getLong("certificate_id"),
            rs.getInt("certificate_version"),
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
                            + "(batch_id, reading_key, batch_revision, measured_at, reading_value, submitted_by, "
                            + "certificate_id, certificate_version, excluded, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, reading.batchId());
            ps.setString(2, reading.readingKey());
            ps.setInt(3, reading.batchRevision());
            ps.setObject(4, JdbcTimes.toDb(reading.measuredAt()));
            ps.setBigDecimal(5, reading.value());
            ps.setString(6, reading.submittedBy());
            ps.setLong(7, reading.certificateId());
            ps.setInt(8, reading.certificateVersion());
            ps.setObject(9, JdbcTimes.toDb(reading.createdAt()));
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
     * 查询批次某修订版本下的全部读数（按 ID 升序，即提交顺序）。
     */
    public List<BatchReading> findByBatchAndRevision(long batchId, int revision) {
        return jdbc.query("SELECT * FROM batch_reading WHERE batch_id = ? AND batch_revision = ? ORDER BY id",
                MAPPER, batchId, revision);
    }

    /**
     * 查询批次全部读数（按 ID 升序）。
     */
    public List<BatchReading> findByBatch(long batchId) {
        return jdbc.query("SELECT * FROM batch_reading WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }

    /**
     * 将读数置为已排除（须在持有批次行锁的事务内调用）。
     */
    public void markExcluded(long id, Instant excludedAt) {
        jdbc.update("UPDATE batch_reading SET excluded = TRUE, excluded_at = ? WHERE id = ?",
                JdbcTimes.toDb(excludedAt), id);
    }
}