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
 * 测量记录持久化。同一 measurement_key 支持多修订版本：(measurement_key, revision) 唯一，
 * is_latest 标记唯一最新版；修订与放行的状态流转由行锁在事务内串行化。
 */
@Repository
public class MeasurementRepository {

    private static final RowMapper<Measurement> MAPPER = (rs, rowNum) -> new Measurement(
            rs.getLong("id"),
            rs.getString("measurement_key"),
            rs.getInt("revision"),
            rs.getBoolean("is_latest"),
            rs.getString("request_id"),
            rs.getString("revision_reason"),
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
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public MeasurementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入测量记录（一个修订版本）并返回生成的 ID；
     * (measurement_key, revision) 或 (measurement_key, request_id) 冲突时抛出 DuplicateKeyException。
     */
    public long insert(Measurement measurement) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement "
                            + "(measurement_key, revision, is_latest, request_id, revision_reason, "
                            + "instrument_id, measured_at, raw_reading, lower_limit, upper_limit, "
                            + "submitted_by, certificate_id, computed_value, passed, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, measurement.measurementKey());
            ps.setInt(2, measurement.revision());
            ps.setBoolean(3, measurement.isLatest());
            ps.setString(4, measurement.requestId());
            ps.setString(5, measurement.revisionReason());
            ps.setString(6, measurement.instrumentId());
            ps.setObject(7, JdbcTimes.toDb(measurement.measuredAt()));
            ps.setBigDecimal(8, measurement.rawReading());
            ps.setBigDecimal(9, measurement.lowerLimit());
            ps.setBigDecimal(10, measurement.upperLimit());
            ps.setString(11, measurement.submittedBy());
            ps.setLong(12, measurement.certificateId());
            ps.setBigDecimal(13, measurement.computedValue());
            ps.setBoolean(14, measurement.passed());
            ps.setString(15, measurement.status().name());
            ps.setObject(16, JdbcTimes.toDb(measurement.createdAt()));
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
     * 查询某测量键的最新版本（不加锁）。
     */
    public Optional<Measurement> findLatestByKey(String key) {
        return jdbc.query("SELECT * FROM measurement WHERE measurement_key = ? AND is_latest = TRUE",
                        MAPPER, key)
                .stream().findFirst();
    }

    /**
     * 查询某测量键的全部版本并加行锁（须在事务内调用），用于修订时串行化同一键的并发修订。
     */
    public List<Measurement> findAllByKeyForUpdate(String key) {
        return jdbc.query("SELECT * FROM measurement WHERE measurement_key = ? ORDER BY id FOR UPDATE",
                MAPPER, key);
    }

    /**
     * 按测量键与修订号查询并加行锁（须在事务内调用），用于版本化放行时串行化状态流转。
     */
    public Optional<Measurement> findByKeyAndRevisionForUpdate(String key, int revision) {
        return jdbc.query("SELECT * FROM measurement WHERE measurement_key = ? AND revision = ? FOR UPDATE",
                        MAPPER, key, revision)
                .stream().findFirst();
    }

    /**
     * 查询某测量键的全部版本历史（按修订号升序，不加锁）。
     */
    public List<Measurement> findHistory(String key) {
        return jdbc.query("SELECT * FROM measurement WHERE measurement_key = ? ORDER BY revision",
                MAPPER, key);
    }

    /**
     * 将指定版本置为非最新（须在持有该键行锁的事务内调用），与插入新版同事务原子生效。
     */
    public void markNotLatest(long id) {
        jdbc.update("UPDATE measurement SET is_latest = FALSE WHERE id = ?", id);
    }

    /**
     * 将测量记录置为已放行（须在持有行锁的事务内调用）。
     */
    public void markReleased(long id) {
        jdbc.update("UPDATE measurement SET status = ? WHERE id = ?",
                MeasurementStatus.RELEASED.name(), id);
    }

    /**
     * 查询当前可用结果：最新版、已放行且证书未撤销，每个测量键至多一条。
     * instrumentId 为 null 时不过滤仪器。
     */
    public List<Measurement> findUsable(String instrumentId) {
        String base = "SELECT m.* FROM measurement m "
                + "JOIN calibration_certificate c ON c.id = m.certificate_id "
                + "WHERE m.is_latest = TRUE AND m.status = 'RELEASED' AND c.revoked = FALSE ";
        if (instrumentId == null) {
            return jdbc.query(base + "ORDER BY m.id", MAPPER);
        }
        return jdbc.query(base + "AND m.instrument_id = ? ORDER BY m.id", MAPPER, instrumentId);
    }
}
