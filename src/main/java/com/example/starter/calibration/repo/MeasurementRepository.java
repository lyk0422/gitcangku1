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

import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;

/**
 * 测量记录持久化。(measurement_key, version) 唯一；同一 measurement_key 构成修订链，
 * predecessor_id 唯一保证每个被驳回测量至多一个直接后继修订。状态流转由放行/复核事务在行锁内完成。
 */
@Repository
public class MeasurementRepository {

    private static final RowMapper<Measurement> MAPPER = (rs, rowNum) -> new Measurement(
            rs.getLong("id"),
            rs.getString("measurement_key"),
            rs.getInt("version"),
            rs.getString("instrument_id"),
            JdbcTimes.fromDb(rs.getObject("measured_at", LocalDateTime.class)),
            rs.getBigDecimal("raw_reading"),
            rs.getBigDecimal("lower_limit"),
            rs.getBigDecimal("upper_limit"),
            rs.getString("submitted_by"),
            rs.getLong("certificate_id"),
            rs.getBigDecimal("computed_value"),
            rs.getBoolean("passed"),
            MeasurementStatus.valueOf(rs.getString("status")),
            rs.getString("note"),
            rs.getObject("predecessor_id", Long.class),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public MeasurementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入测量记录并返回生成的 ID；(measurement_key, version) 或 predecessor_id 冲突时抛出 DuplicateKeyException。
     */
    public long insert(Measurement measurement) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement "
                            + "(measurement_key, version, instrument_id, measured_at, raw_reading, lower_limit, "
                            + "upper_limit, submitted_by, certificate_id, computed_value, passed, status, note, "
                            + "predecessor_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, measurement.measurementKey());
            ps.setInt(2, measurement.version());
            ps.setString(3, measurement.instrumentId());
            ps.setObject(4, JdbcTimes.toDb(measurement.measuredAt()));
            ps.setBigDecimal(5, measurement.rawReading());
            ps.setBigDecimal(6, measurement.lowerLimit());
            ps.setBigDecimal(7, measurement.upperLimit());
            ps.setString(8, measurement.submittedBy());
            ps.setLong(9, measurement.certificateId());
            ps.setBigDecimal(10, measurement.computedValue());
            ps.setBoolean(11, measurement.passed());
            ps.setString(12, measurement.status().name());
            ps.setString(13, measurement.note());
            if (measurement.predecessorId() == null) {
                ps.setObject(14, null);
            } else {
                ps.setLong(14, measurement.predecessorId());
            }
            ps.setObject(15, JdbcTimes.toDb(measurement.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 ID 查询（不加锁）。
     */
    public Optional<Measurement> findById(long id) {
        return jdbc.query("SELECT * FROM measurement WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行锁（须在事务内调用）。
     */
    public Optional<Measurement> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM measurement WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按测量键查询最新版本（不加锁）。
     */
    public Optional<Measurement> findByKey(String key) {
        return jdbc.query("SELECT * FROM measurement WHERE measurement_key = ? "
                        + "ORDER BY version DESC LIMIT 1", MAPPER, key)
                .stream().findFirst();
    }

    /**
     * 按测量键查询最新版本并加行锁（须在事务内调用），用于放行时串行化状态流转。
     */
    public Optional<Measurement> findByKeyForUpdate(String key) {
        return jdbc.query("SELECT * FROM measurement WHERE measurement_key = ? "
                        + "ORDER BY version DESC LIMIT 1 FOR UPDATE", MAPPER, key)
                .stream().findFirst();
    }

    /**
     * 按测量键与版本精确查询（不加锁）。
     */
    public Optional<Measurement> findByKeyAndVersion(String key, int version) {
        return jdbc.query("SELECT * FROM measurement WHERE measurement_key = ? AND version = ?",
                        MAPPER, key, version)
                .stream().findFirst();
    }

    /**
     * 按测量键与版本精确查询并加行锁（须在事务内调用），用于修订创建时串行化。
     */
    public Optional<Measurement> findByKeyAndVersionForUpdate(String key, int version) {
        return jdbc.query("SELECT * FROM measurement WHERE measurement_key = ? AND version = ? FOR UPDATE",
                        MAPPER, key, version)
                .stream().findFirst();
    }

    /**
     * 查询某测量键的完整修订链（按版本升序）。
     */
    public List<Measurement> findChain(String key) {
        return jdbc.query("SELECT * FROM measurement WHERE measurement_key = ? ORDER BY version",
                MAPPER, key);
    }

    /**
     * 查询某批次放行时使用的全部测量记录（按放行记录顺序）。
     */
    public List<Measurement> findByBatchId(String batchId) {
        return jdbc.query("SELECT m.* FROM measurement m "
                        + "JOIN release_record r ON r.measurement_id = m.id "
                        + "WHERE r.batch_id = ? ORDER BY r.id", MAPPER, batchId);
    }

    /**
     * 将测量记录置为已放行（须在持有行锁的事务内调用）。
     */
    public void markReleased(long id) {
        jdbc.update("UPDATE measurement SET status = ? WHERE id = ?",
                MeasurementStatus.RELEASED.name(), id);
    }

    /**
     * 将测量记录置为复核驳回（须在持有行锁的事务内调用）。
     */
    public void markRejected(long id) {
        jdbc.update("UPDATE measurement SET status = ? WHERE id = ?",
                MeasurementStatus.REJECTED.name(), id);
    }

    /**
     * 查询当前可用结果：已放行、证书未撤销且所在放行批次仍生效。instrumentId 为 null 时不过滤仪器。
     */
    public List<Measurement> findUsable(String instrumentId) {
        String base = "SELECT m.* FROM measurement m "
                + "JOIN calibration_certificate c ON c.id = m.certificate_id "
                + "WHERE m.status = 'RELEASED' AND c.revoked = FALSE "
                + "AND EXISTS (SELECT 1 FROM release_record r "
                + "JOIN release_batch b ON b.batch_id = r.batch_id "
                + "WHERE r.measurement_id = m.id AND b.status = 'RELEASED') ";
        if (instrumentId == null) {
            return jdbc.query(base + "ORDER BY m.id", MAPPER);
        }
        return jdbc.query(base + "AND m.instrument_id = ? ORDER BY m.id", MAPPER, instrumentId);
    }
}
