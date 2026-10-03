package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
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
 * 批次读数持久化。reading_key 全局唯一作为幂等键；排除标记由 REJECT 裁决事务写入。
 */
@Repository
public class ReadingRepository {

    private static final RowMapper<BatchReading> MAPPER = (rs, rowNum) -> new BatchReading(
            rs.getLong("id"),
            rs.getString("reading_key"),
            rs.getLong("batch_id"),
            JdbcTimes.fromDb(rs.getObject("measured_at", LocalDateTime.class)),
            rs.getBigDecimal("raw_reading"),
            rs.getString("submitted_by"),
            rs.getLong("certificate_id"),
            rs.getInt("certificate_version"),
            rs.getInt("batch_revision"),
            rs.getBoolean("excluded"),
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
                            + "(reading_key, batch_id, measured_at, raw_reading, submitted_by, "
                            + "certificate_id, certificate_version, batch_revision, excluded, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, reading.readingKey());
            ps.setLong(2, reading.batchId());
            ps.setObject(3, JdbcTimes.toDb(reading.measuredAt()));
            ps.setBigDecimal(4, reading.rawReading());
            ps.setString(5, reading.submittedBy());
            ps.setLong(6, reading.certificateId());
            ps.setInt(7, reading.certificateVersion());
            ps.setInt(8, reading.batchRevision());
            ps.setBoolean(9, reading.excluded());
            ps.setObject(10, JdbcTimes.toDb(reading.createdAt()));
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
     * 查询批次全部读数（按 ID 升序，即提交顺序）。
     */
    public List<BatchReading> findByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM batch_reading WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }

    /**
     * 将读数标记为已排除（须在持有批次行锁的 REJECT 裁决事务内调用）。
     */
    public void markExcluded(long id) {
        jdbc.update("UPDATE batch_reading SET excluded = TRUE WHERE id = ?", id);
    }
}