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
 * 测量记录持久化。measurement_key 全局唯一作为幂等键；
 * 状态流转（放行/驳回/重算）由服务层在行锁与事务内完成。
 */
@Repository
public class MeasurementRepository {

    private static final String COLUMNS = "id, measurement_key, instrument_id, instrument_model, measured_at, "
            + "raw_reading, lower_limit, upper_limit, submitted_by, certificate_id, computed_value, passed, "
            + "temperature, humidity, uncertainty, compensation_profile_id, compensated_value, compensated_passed, "
            + "current_version, status, created_at";

    private static final RowMapper<Measurement> MAPPER = (rs, rowNum) -> new Measurement(
            rs.getLong("id"),
            rs.getString("measurement_key"),
            rs.getString("instrument_id"),
            rs.getString("instrument_model"),
            JdbcTimes.fromDb(rs.getObject("measured_at", LocalDateTime.class)),
            rs.getBigDecimal("raw_reading"),
            rs.getBigDecimal("lower_limit"),
            rs.getBigDecimal("upper_limit"),
            rs.getString("submitted_by"),
            rs.getLong("certificate_id"),
            rs.getBigDecimal("computed_value"),
            rs.getBoolean("passed"),
            rs.getBigDecimal("temperature"),
            rs.getBigDecimal("humidity"),
            rs.getBigDecimal("uncertainty"),
            (Long) rs.getObject("compensation_profile_id"),
            rs.getBigDecimal("compensated_value"),
            (Boolean) rs.getObject("compensated_passed"),
            rs.getInt("current_version"),
            MeasurementStatus.valueOf(rs.getString("status")),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public MeasurementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入测量记录（含 v1 的当前版本冗余列）并返回生成的 ID；measurement_key 冲突时抛出 DuplicateKeyException。
     */
    public long insert(Measurement measurement) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement (measurement_key, instrument_id, instrument_model, measured_at, "
                            + "raw_reading, lower_limit, upper_limit, submitted_by, certificate_id, computed_value, "
                            + "passed, temperature, humidity, uncertainty, compensation_profile_id, compensated_value, "
                            + "compensated_passed, current_version, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, measurement.measurementKey());
            ps.setString(2, measurement.instrumentId());
            ps.setString(3, measurement.instrumentModel());
            ps.setObject(4, JdbcTimes.toDb(measurement.measuredAt()));
            ps.setBigDecimal(5, measurement.rawReading());
            ps.setBigDecimal(6, measurement.lowerLimit());
            ps.setBigDecimal(7, measurement.upperLimit());
            ps.setString(8, measurement.submittedBy());
            ps.setLong(9, measurement.certificateId());
            ps.setBigDecimal(10, measurement.computedValue());
            ps.setBoolean(11, measurement.passed());
            ps.setBigDecimal(12, measurement.temperature());
            ps.setBigDecimal(13, measurement.humidity());
            ps.setBigDecimal(14, measurement.uncertainty());
            setNullableLong(ps, 15, measurement.compensationProfileId());
            ps.setBigDecimal(16, measurement.compensatedValue());
            ps.setObject(17, measurement.compensatedPassed());
            ps.setInt(18, measurement.currentVersion());
            ps.setString(19, measurement.status().name());
            ps.setObject(20, JdbcTimes.toDb(measurement.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 重算后用新版本覆盖当前版本冗余列，并将状态复位为待放行（须在持锁事务内调用）。
     */
    public void applyVersion(Measurement m) {
        jdbc.update("UPDATE measurement SET certificate_id = ?, computed_value = ?, passed = ?, temperature = ?, "
                        + "humidity = ?, uncertainty = ?, compensation_profile_id = ?, compensated_value = ?, "
                        + "compensated_passed = ?, current_version = ?, status = 'PENDING' WHERE id = ?",
                m.certificateId(),
                m.computedValue(),
                m.passed(),
                m.temperature(),
                m.humidity(),
                m.uncertainty(),
                m.compensationProfileId(),
                m.compensatedValue(),
                m.compensatedPassed(),
                m.currentVersion(),
                m.id());
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setLong(index, value);
        }
    }

    /**
     * 按测量键查询（不加锁）。
     */
    public Optional<Measurement> findByKey(String key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM measurement WHERE measurement_key = ?", MAPPER, key)
                .stream().findFirst();
    }

    /**
     * 按测量键查询并加行锁（须在事务内调用），用于放行/驳回/重算时串行化状态流转。
     */
    public Optional<Measurement> findByKeyForUpdate(String key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM measurement WHERE measurement_key = ? FOR UPDATE",
                MAPPER, key)
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
     * 将测量记录置为已驳回（须在持有行锁的事务内调用）。
     */
    public void markRejected(long id) {
        jdbc.update("UPDATE measurement SET status = ? WHERE id = ?",
                MeasurementStatus.REJECTED.name(), id);
    }

    /**
     * 查询当前可用结果：已放行且证书未撤销。instrumentId 为 null 时不过滤仪器。
     */
    public List<Measurement> findUsable(String instrumentId) {
        String base = "SELECT m.* FROM measurement m "
                + "JOIN calibration_certificate c ON c.id = m.certificate_id "
                + "WHERE m.status = 'RELEASED' AND c.revoked = FALSE ";
        if (instrumentId == null) {
            return jdbc.query(base + "ORDER BY m.id", MAPPER);
        }
        return jdbc.query(base + "AND m.instrument_id = ? ORDER BY m.id", MAPPER, instrumentId);
    }
}
