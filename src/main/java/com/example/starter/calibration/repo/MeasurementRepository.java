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
 * 测量记录持久化（当前生效版本）。measurement_key 全局唯一作为幂等键；
 * 状态流转由放行事务在行锁内完成；替换标准器时更新当前版本字段并由版本表追加历史。
 */
@Repository
public class MeasurementRepository {

    private static final String COLUMNS = "id, measurement_key, instrument_id, standard_id, measured_at, "
            + "raw_reading, lower_limit, upper_limit, submitted_by, certificate_id, certificate_version, "
            + "version_no, computed_value, expanded_uncertainty, uncertainty_version, reference_key, "
            + "passed, status, created_at";

    static final RowMapper<Measurement> MAPPER = (rs, rowNum) -> new Measurement(
            rs.getLong("id"),
            rs.getString("measurement_key"),
            rs.getString("instrument_id"),
            rs.getString("standard_id"),
            JdbcTimes.fromDb(rs.getObject("measured_at", LocalDateTime.class)),
            rs.getBigDecimal("raw_reading"),
            rs.getBigDecimal("lower_limit"),
            rs.getBigDecimal("upper_limit"),
            rs.getString("submitted_by"),
            rs.getLong("certificate_id"),
            rs.getString("certificate_version"),
            rs.getInt("version_no"),
            rs.getBigDecimal("computed_value"),
            rs.getBigDecimal("expanded_uncertainty"),
            rs.getString("uncertainty_version"),
            rs.getString("reference_key"),
            rs.getBoolean("passed"),
            MeasurementStatus.valueOf(rs.getString("status")),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public MeasurementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入测量记录（版本 1）并返回生成的 ID；measurement_key 冲突时抛出 DuplicateKeyException。
     */
    public long insert(Measurement measurement) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement "
                            + "(measurement_key, instrument_id, standard_id, measured_at, raw_reading, "
                            + "lower_limit, upper_limit, submitted_by, certificate_id, certificate_version, "
                            + "version_no, computed_value, expanded_uncertainty, uncertainty_version, "
                            + "reference_key, passed, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, measurement.measurementKey());
            ps.setString(2, measurement.instrumentId());
            ps.setString(3, measurement.standardId());
            ps.setObject(4, JdbcTimes.toDb(measurement.measuredAt()));
            ps.setBigDecimal(5, measurement.rawReading());
            ps.setBigDecimal(6, measurement.lowerLimit());
            ps.setBigDecimal(7, measurement.upperLimit());
            ps.setString(8, measurement.submittedBy());
            ps.setLong(9, measurement.certificateId());
            ps.setString(10, measurement.certificateVersion());
            ps.setInt(11, measurement.versionNo());
            ps.setBigDecimal(12, measurement.computedValue());
            ps.setBigDecimal(13, measurement.expandedUncertainty());
            ps.setString(14, measurement.uncertaintyVersion());
            ps.setString(15, measurement.referenceKey());
            ps.setBoolean(16, measurement.passed());
            ps.setString(17, measurement.status().name());
            ps.setObject(18, JdbcTimes.toDb(measurement.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按测量键查询（不加锁）。
     */
    public Optional<Measurement> findByKey(String key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM measurement WHERE measurement_key = ?", MAPPER, key)
                .stream().findFirst();
    }

    /**
     * 按测量键查询并加行锁（须在事务内调用），用于放行与重算的并发互斥。
     */
    public Optional<Measurement> findByKeyForUpdate(String key) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM measurement WHERE measurement_key = ? FOR UPDATE", MAPPER, key)
                .stream().findFirst();
    }

    /**
     * 重算后将当前版本切换为新版本（须在持有测量行锁的事务内调用）；
     * 仅未放行（PENDING）测量允许替换，调用方负责该前置校验。
     */
    public void switchVersion(long id, long certificateId, String standardId, String certificateVersion,
                              int versionNo, java.math.BigDecimal computed,
                              java.math.BigDecimal expandedUncertainty, String uncertaintyVersion,
                              String referenceKey, boolean passed) {
        jdbc.update(
                "UPDATE measurement SET standard_id = ?, certificate_id = ?, certificate_version = ?, "
                        + "version_no = ?, computed_value = ?, expanded_uncertainty = ?, "
                        + "uncertainty_version = ?, reference_key = ?, passed = ? WHERE id = ?",
                standardId, certificateId, certificateVersion, versionNo, computed,
                expandedUncertainty, uncertaintyVersion, referenceKey, passed, id);
    }

    /**
     * 将测量记录置为已放行（须在持有行锁的事务内调用）。
     */
    public void markReleased(long id) {
        jdbc.update("UPDATE measurement SET status = ? WHERE id = ?",
                MeasurementStatus.RELEASED.name(), id);
    }

    /**
     * 查询当前可用结果：已放行且证书未撤销。instrumentId 为 null 时不过滤仪器。
     */
    public List<Measurement> findUsable(String instrumentId) {
        String select = "SELECT m.id, m.measurement_key, m.instrument_id, m.standard_id, m.measured_at, "
                + "m.raw_reading, m.lower_limit, m.upper_limit, m.submitted_by, m.certificate_id, "
                + "m.certificate_version, m.version_no, m.computed_value, m.expanded_uncertainty, "
                + "m.uncertainty_version, m.reference_key, m.passed, m.status, m.created_at "
                + "FROM measurement m "
                + "JOIN calibration_certificate c ON c.id = m.certificate_id "
                + "WHERE m.status = 'RELEASED' AND c.revoked = FALSE ";
        if (instrumentId == null) {
            return jdbc.query(select + "ORDER BY m.id", MAPPER);
        }
        return jdbc.query(select + "AND m.instrument_id = ? ORDER BY m.id", MAPPER, instrumentId);
    }
}
