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

import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;

/**
 * 测量记录持久化。measurement_key 全局唯一作为幂等键；reference_key 全局唯一支持同键重放；
 * 状态流转与版本切换由放行／重算事务在行锁内完成。
 */
@Repository
public class MeasurementRepository {

    private static final RowMapper<Measurement> MAPPER = (rs, rowNum) -> new Measurement(
            rs.getLong("id"),
            rs.getString("measurement_key"),
            rs.getString("batch_id"),
            rs.getString("reference_key"),
            rs.getString("reference_fingerprint"),
            rs.getString("instrument_id"),
            JdbcTimes.fromDb(rs.getObject("measured_at", LocalDateTime.class)),
            rs.getBigDecimal("raw_reading"),
            rs.getBigDecimal("lower_limit"),
            rs.getBigDecimal("upper_limit"),
            rs.getString("submitted_by"),
            rs.getLong("certificate_id"),
            rs.getString("cert_version"),
            rs.getBigDecimal("compensation_coeff"),
            rs.getString("uncertainty_version"),
            rs.getBigDecimal("computed_value"),
            rs.getBigDecimal("uncertainty"),
            rs.getBoolean("passed"),
            rs.getInt("version"),
            MeasurementStatus.valueOf(rs.getString("status")),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public MeasurementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入测量记录并返回生成的 ID；measurement_key 或 reference_key 冲突时抛出 DuplicateKeyException。
     */
    public long insert(Measurement measurement) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement "
                            + "(measurement_key, batch_id, reference_key, reference_fingerprint, instrument_id, "
                            + "measured_at, raw_reading, lower_limit, upper_limit, submitted_by, certificate_id, "
                            + "cert_version, compensation_coeff, uncertainty_version, computed_value, uncertainty, "
                            + "passed, version, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, measurement.measurementKey());
            ps.setString(2, measurement.batchId());
            ps.setString(3, measurement.referenceKey());
            ps.setString(4, measurement.referenceFingerprint());
            ps.setString(5, measurement.instrumentId());
            ps.setObject(6, JdbcTimes.toDb(measurement.measuredAt()));
            ps.setBigDecimal(7, measurement.rawReading());
            ps.setBigDecimal(8, measurement.lowerLimit());
            ps.setBigDecimal(9, measurement.upperLimit());
            ps.setString(10, measurement.submittedBy());
            ps.setLong(11, measurement.certificateId());
            ps.setString(12, measurement.certVersion());
            ps.setBigDecimal(13, measurement.compensationCoeff());
            ps.setString(14, measurement.uncertaintyVersion());
            ps.setBigDecimal(15, measurement.computedValue());
            ps.setBigDecimal(16, measurement.uncertainty());
            ps.setBoolean(17, measurement.passed());
            ps.setInt(18, measurement.version());
            ps.setString(19, measurement.status().name());
            ps.setObject(20, JdbcTimes.toDb(measurement.createdAt()));
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
     * 按 ID 查询（不加锁）。
     */
    public Optional<Measurement> findById(long id) {
        return jdbc.query("SELECT * FROM measurement WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按幂等引用键查询（不加锁），用于同键重放判定。
     */
    public Optional<Measurement> findByReferenceKey(String referenceKey) {
        return jdbc.query("SELECT * FROM measurement WHERE reference_key = ?", MAPPER, referenceKey)
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
     * 按提交批次查询并加行锁（须在事务内调用），用于替换标准器重算时串行化。
     */
    public List<Measurement> findByBatchIdForUpdate(String batchId) {
        return jdbc.query("SELECT * FROM measurement WHERE batch_id = ? ORDER BY id FOR UPDATE",
                MAPPER, batchId);
    }

    /**
     * 将测量记录置为已放行（须在持有行锁的事务内调用）。
     */
    public void markReleased(long id) {
        jdbc.update("UPDATE measurement SET status = ? WHERE id = ?",
                MeasurementStatus.RELEASED.name(), id);
    }

    /**
     * 应用新的测量版本：切换当前证书引用、版本快照、计算结果与版本号（须在持有行锁的事务内调用）。
     */
    public void applyVersion(long id, int version, Certificate cert,
                             java.math.BigDecimal computedValue, java.math.BigDecimal uncertainty,
                             boolean passed) {
        jdbc.update("UPDATE measurement SET certificate_id = ?, cert_version = ?, compensation_coeff = ?, "
                        + "uncertainty_version = ?, computed_value = ?, uncertainty = ?, passed = ?, version = ? "
                        + "WHERE id = ?",
                cert.id(), cert.certVersion(), cert.compensationCoeff(), cert.uncertaintyVersion(),
                computedValue, uncertainty, passed, version, id);
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
