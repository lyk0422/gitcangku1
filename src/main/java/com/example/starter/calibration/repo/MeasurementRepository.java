package com.example.starter.calibration.repo;

import java.math.BigInteger;
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
import com.example.starter.calibration.model.MeasurementMode;
import com.example.starter.calibration.model.MeasurementStatus;

/**
 * 测量记录持久化。measurement_key 全局唯一作为幂等键；状态流转由放行事务在行锁内完成。
 * DRIFT 模式的校正值以精确有理数（分子/分母字符串）保存，不丢精度。
 */
@Repository
public class MeasurementRepository {

    private static final RowMapper<Measurement> MAPPER = (rs, rowNum) -> new Measurement(
            rs.getLong("id"),
            rs.getString("measurement_key"),
            rs.getString("instrument_id"),
            JdbcTimes.fromDb(rs.getObject("measured_at", LocalDateTime.class)),
            rs.getBigDecimal("raw_reading"),
            rs.getBigDecimal("lower_limit"),
            rs.getBigDecimal("upper_limit"),
            rs.getString("submitted_by"),
            rs.getLong("certificate_id"),
            MeasurementMode.valueOf(rs.getString("mode")),
            rs.getObject("profile_t0_id", Long.class),
            rs.getObject("profile_t1_id", Long.class),
            rs.getBigDecimal("computed_value"),
            toBigInteger(rs.getString("computed_num")),
            toBigInteger(rs.getString("computed_den")),
            rs.getBoolean("passed"),
            MeasurementStatus.valueOf(rs.getString("status")),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private static BigInteger toBigInteger(String value) {
        return value == null ? null : new BigInteger(value);
    }

    private final JdbcTemplate jdbc;

    public MeasurementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入测量记录并返回生成的 ID；measurement_key 冲突时抛出 DuplicateKeyException。
     */
    public long insert(Measurement measurement) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement "
                            + "(measurement_key, instrument_id, measured_at, raw_reading, lower_limit, upper_limit, "
                            + "submitted_by, certificate_id, mode, profile_t0_id, profile_t1_id, "
                            + "computed_value, computed_num, computed_den, passed, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, measurement.measurementKey());
            ps.setString(2, measurement.instrumentId());
            ps.setObject(3, JdbcTimes.toDb(measurement.measuredAt()));
            ps.setBigDecimal(4, measurement.rawReading());
            ps.setBigDecimal(5, measurement.lowerLimit());
            ps.setBigDecimal(6, measurement.upperLimit());
            ps.setString(7, measurement.submittedBy());
            ps.setLong(8, measurement.certificateId());
            ps.setString(9, measurement.mode().name());
            if (measurement.profileT0Id() == null) {
                ps.setNull(10, java.sql.Types.BIGINT);
                ps.setNull(11, java.sql.Types.BIGINT);
            } else {
                ps.setLong(10, measurement.profileT0Id());
                ps.setLong(11, measurement.profileT1Id());
            }
            if (measurement.computedValue() == null) {
                ps.setNull(12, java.sql.Types.DECIMAL);
            } else {
                ps.setBigDecimal(12, measurement.computedValue());
            }
            ps.setString(13, measurement.computedNum() == null ? null : measurement.computedNum().toString());
            ps.setString(14, measurement.computedDen() == null ? null : measurement.computedDen().toString());
            ps.setBoolean(15, measurement.passed());
            ps.setString(16, measurement.status().name());
            ps.setObject(17, JdbcTimes.toDb(measurement.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按测量键查询（不加锁）。
     */
    public Optional<Measurement> findByKey(String key) {
        return jdbc.query("SELECT * FROM measurement WHERE measurement_key = ?", MAPPER, key)
                .stream().findFirst();
    }

    /**
     * 按测量键查询并加行锁（须在事务内调用），用于放行时串行化状态流转。
     */
    public Optional<Measurement> findByKeyForUpdate(String key) {
        return jdbc.query("SELECT * FROM measurement WHERE measurement_key = ? FOR UPDATE", MAPPER, key)
                .stream().findFirst();
    }

    /**
     * 将测量记录置为已放行（须在持有行锁的事务内调用）。
     */
    public void markReleased(long id) {
        jdbc.update("UPDATE measurement SET status = ? WHERE id = ?",
                MeasurementStatus.RELEASED.name(), id);
    }

    /**
     * 查询当前可用结果：已放行且全部证据（证书及 DRIFT 模式的两份漂移档案）未撤销。
     * instrumentId 为 null 时不过滤仪器。
     */
    public List<Measurement> findUsable(String instrumentId) {
        String base = "SELECT m.* FROM measurement m "
                + "JOIN calibration_certificate c ON c.id = m.certificate_id "
                + "LEFT JOIN drift_profile p0 ON p0.id = m.profile_t0_id "
                + "LEFT JOIN drift_profile p1 ON p1.id = m.profile_t1_id "
                + "WHERE m.status = 'RELEASED' AND c.revoked = FALSE "
                + "AND (m.mode <> 'DRIFT' OR (p0.revoked = FALSE AND p1.revoked = FALSE)) ";
        if (instrumentId == null) {
            return jdbc.query(base + "ORDER BY m.id", MAPPER);
        }
        return jdbc.query(base + "AND m.instrument_id = ? ORDER BY m.id", MAPPER, instrumentId);
    }
}
