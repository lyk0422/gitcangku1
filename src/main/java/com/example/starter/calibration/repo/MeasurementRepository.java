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

import com.example.starter.calibration.model.EnvRecord;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;

/**
 * 测量记录持久化（版本化）。同一逻辑测量用 measurement_key 标识，重算追加 (measurement_key, version_no) 新版本行；
 * 状态流转由放行/驳回事务在最新版本行锁内完成。
 */
@Repository
public class MeasurementRepository {

    private static final RowMapper<Measurement> MAPPER = (rs, rowNum) -> {
        String model = rs.getString("instrument_model");
        return new Measurement(
            rs.getLong("id"),
            rs.getLong("root_id"),
            rs.getInt("version_no"),
            rs.getString("measurement_key"),
            rs.getString("instrument_id"),
            model,
            JdbcTimes.fromDb(rs.getObject("measured_at", LocalDateTime.class)),
            rs.getBigDecimal("raw_reading"),
            rs.getBigDecimal("lower_limit"),
            rs.getBigDecimal("upper_limit"),
            rs.getBigDecimal("uncertainty_limit"),
            model == null ? null : new EnvRecord(
                    model,
                    rs.getBigDecimal("temperature_c"),
                    rs.getBigDecimal("humidity_pct")),
            rs.getString("submitted_by"),
            rs.getLong("certificate_id"),
            (Long) rs.getObject("coefficient_id"),
            rs.getBigDecimal("computed_value"),
            rs.getBigDecimal("compensation_value"),
            rs.getBigDecimal("compensated_value"),
            rs.getBigDecimal("uncertainty"),
            rs.getBoolean("passed"),
            (Boolean) rs.getObject("passed_after_comp"),
            MeasurementStatus.valueOf(rs.getString("status")),
            rs.getString("rejected_by"),
            JdbcTimes.fromDb(rs.getObject("rejected_at", LocalDateTime.class)),
            rs.getString("reject_reason"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));
    };

    private final JdbcTemplate jdbc;

    public MeasurementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入测量版本行并返回生成的行 ID；首版本（rootId=0）回填 root_id=自身行 ID。
     */
    public long insert(Measurement measurement) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement "
                            + "(root_id, version_no, measurement_key, instrument_id, instrument_model, measured_at, "
                            + "raw_reading, lower_limit, upper_limit, uncertainty_limit, temperature_c, humidity_pct, "
                            + "submitted_by, certificate_id, coefficient_id, computed_value, compensation_value, "
                            + "compensated_value, uncertainty, passed, passed_after_comp, status, "
                            + "rejected_by, rejected_at, reject_reason, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            long rootId = measurement.rootId();
            ps.setLong(1, rootId);
            ps.setInt(2, measurement.versionNo());
            ps.setString(3, measurement.measurementKey());
            ps.setString(4, measurement.instrumentId());
            ps.setString(5, measurement.instrumentModel());
            ps.setObject(6, JdbcTimes.toDb(measurement.measuredAt()));
            ps.setBigDecimal(7, measurement.rawReading());
            ps.setBigDecimal(8, measurement.lowerLimit());
            ps.setBigDecimal(9, measurement.upperLimit());
            ps.setBigDecimal(10, measurement.uncertaintyLimit());
            ps.setBigDecimal(11, measurement.hasEnvironment() ? measurement.env().temperatureC() : null);
            ps.setBigDecimal(12, measurement.hasEnvironment() ? measurement.env().humidityPct() : null);
            ps.setString(13, measurement.submittedBy());
            ps.setLong(14, measurement.certificateId());
            Long coeffId = measurement.coefficientId();
            if (coeffId == null) {
                ps.setObject(15, null);
            } else {
                ps.setLong(15, coeffId);
            }
            ps.setBigDecimal(16, measurement.computedValue());
            ps.setBigDecimal(17, measurement.compensationValue());
            ps.setBigDecimal(18, measurement.compensatedValue());
            ps.setBigDecimal(19, measurement.uncertainty());
            ps.setBoolean(20, measurement.passed());
            Boolean passedAfter = measurement.passedAfterComp();
            if (passedAfter == null) {
                ps.setObject(21, null);
            } else {
                ps.setBoolean(21, passedAfter);
            }
            ps.setString(22, measurement.status().name());
            ps.setString(23, measurement.rejectedBy());
            ps.setObject(24, JdbcTimes.toDb(measurement.rejectedAt()));
            ps.setString(25, measurement.rejectReason());
            ps.setObject(26, JdbcTimes.toDb(measurement.createdAt()));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        if (measurement.rootId() == 0L) {
            jdbc.update("UPDATE measurement SET root_id = id WHERE id = ?", id);
        }
        return id;
    }

    /**
     * 查询逻辑测量的最新版本（不加锁）。
     */
    public Optional<Measurement> findLatestByKey(String key) {
        return jdbc.query(
                "SELECT * FROM measurement WHERE measurement_key = ? ORDER BY version_no DESC LIMIT 1",
                MAPPER, key).stream().findFirst();
    }

    /**
     * 查询逻辑测量的最新版本并对该行加锁（须在事务内调用），串行化放行/驳回/重算。
     */
    public Optional<Measurement> findLatestByKeyForUpdate(String key) {
        return jdbc.query(
                "SELECT * FROM measurement WHERE measurement_key = ? ORDER BY version_no DESC LIMIT 1 FOR UPDATE",
                MAPPER, key).stream().findFirst();
    }

    /**
     * 按行 ID 查询最新版本并加行锁（须在事务内调用）。
     */
    public Optional<Measurement> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM measurement WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按行 ID 查询（不加锁）。
     */
    public Optional<Measurement> findById(long id) {
        return jdbc.query("SELECT * FROM measurement WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询逻辑测量全部版本（重算链，按版本号升序）。
     */
    public List<Measurement> findChain(String key) {
        return jdbc.query(
                "SELECT * FROM measurement WHERE measurement_key = ? ORDER BY version_no", MAPPER, key);
    }

    /**
     * 按逻辑测量键与版本号查询指定不可变版本行（用于幂等重放定位首次固化版本）。
     */
    public Optional<Measurement> findByKeyAndVersion(String key, int versionNo) {
        return jdbc.query(
                "SELECT * FROM measurement WHERE measurement_key = ? AND version_no = ?",
                MAPPER, key, versionNo).stream().findFirst();
    }

    /**
     * 将某版本行置为已放行（须在持有行锁的事务内调用）。
     */
    public void markReleased(long id) {
        jdbc.update("UPDATE measurement SET status = ? WHERE id = ?",
                MeasurementStatus.RELEASED.name(), id);
    }

    /**
     * 将某版本行置为已驳回（须在持有行锁的事务内调用）。
     */
    public void markRejected(long id, String rejectedBy, java.time.Instant rejectedAt, String reason) {
        jdbc.update(
                "UPDATE measurement SET status = ?, rejected_by = ?, rejected_at = ?, reject_reason = ? WHERE id = ?",
                MeasurementStatus.REJECTED.name(), rejectedBy, JdbcTimes.toDb(rejectedAt), reason, id);
    }

    /**
     * 查询当前可用结果：最新版本已放行且证书未撤销。instrumentId 为 null 时不过滤仪器。
     */
    public List<Measurement> findUsable(String instrumentId) {
        String base = "SELECT m.* FROM measurement m "
                + "JOIN (SELECT measurement_key, MAX(version_no) max_version FROM measurement GROUP BY measurement_key) lv "
                + "ON lv.measurement_key = m.measurement_key AND lv.max_version = m.version_no "
                + "JOIN calibration_certificate c ON c.id = m.certificate_id "
                + "WHERE m.status = 'RELEASED' AND c.revoked = FALSE ";
        if (instrumentId == null) {
            return jdbc.query(base + "ORDER BY m.id", MAPPER);
        }
        return jdbc.query(base + "AND m.instrument_id = ? ORDER BY m.id", MAPPER, instrumentId);
    }
}
