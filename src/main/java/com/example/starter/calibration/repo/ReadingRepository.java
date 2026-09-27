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
 * 批次读数持久化。读数创建后不可改写，仅 excluded 标志由 REJECT 裁决在同一事务内置位。
 */
@Repository
public class ReadingRepository {

    private static final RowMapper<BatchReading> MAPPER = (rs, rowNum) -> new BatchReading(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getInt("batch_revision"),
            rs.getString("request_id"),
            JdbcTimes.fromDb(rs.getObject("measured_at", LocalDateTime.class)),
            rs.getBigDecimal("reading"),
            rs.getString("submitted_by"),
            rs.getLong("certificate_id"),
            rs.getBigDecimal("computed_value"),
            rs.getBoolean("is_outlier"),
            rs.getBoolean("excluded"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入读数并返回生成的 ID。
     */
    public long insert(BatchReading reading) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO batch_reading "
                            + "(batch_id, batch_revision, request_id, measured_at, reading, submitted_by, "
                            + "certificate_id, computed_value, is_outlier, excluded, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, reading.batchId());
            ps.setInt(2, reading.batchRevision());
            ps.setString(3, reading.requestId());
            ps.setObject(4, JdbcTimes.toDb(reading.measuredAt()));
            ps.setBigDecimal(5, reading.reading());
            ps.setString(6, reading.submittedBy());
            ps.setLong(7, reading.certificateId());
            ps.setBigDecimal(8, reading.computedValue());
            ps.setBoolean(9, reading.outlier());
            ps.setBoolean(10, reading.excluded());
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
     * 查询批次指定修订版本下的全部读数（按 ID 升序，即提交顺序）。
     */
    public List<BatchReading> findByBatchAndRevision(long batchId, int revision) {
        return jdbc.query("SELECT * FROM batch_reading WHERE batch_id = ? AND batch_revision = ? ORDER BY id",
                MAPPER, batchId, revision);
    }

    /**
     * 将读数置为已排除（须在持有批次行锁的 REJECT 裁决事务内调用）。
     */
    public void markExcluded(long id) {
        jdbc.update("UPDATE batch_reading SET excluded = TRUE WHERE id = ?", id);
    }
}
