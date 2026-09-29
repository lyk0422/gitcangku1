package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;

/**
 * 测量记录持久化。(measurement_key, version) 唯一；同一业务键修订后追加新版本行，
 * 旧版本置为 SUPERSEDED 或保留 RELEASED 历史，不回写。状态流转由放行事务在行锁内完成。
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
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public MeasurementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入测量记录并返回生成的 ID；(measurement_key, version) 冲突时抛出 DuplicateKeyException。
     */
    public long insert(Measurement measurement) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement "
                            + "(measurement_key, version, instrument_id, measured_at, raw_reading, "
                            + "lower_limit, upper_limit, submitted_by, certificate_id, computed_value, "
                            + "passed, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
            ps.setObject(13, JdbcTimes.toDb(measurement.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 业务键是否已存在（用于区分首次提交与修订）。
     */
    public boolean existsByKey(String key) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key = ?", Integer.class, key);
        return count != null && count > 0;
    }

    /**
     * 按业务键查询最新版本（不加锁）。
     */
    public Optional<Measurement> findLatestByKey(String key) {
        return jdbc.query("SELECT * FROM measurement WHERE measurement_key = ? "
                        + "ORDER BY version DESC LIMIT 1", MAPPER, key)
                .stream().findFirst();
    }

    /**
     * 按业务键查询最新版本并加行锁（须在事务内调用），串行化修订与放行。
     */
    public Optional<Measurement> findLatestByKeyForUpdate(String key) {
        return jdbc.query("SELECT * FROM measurement WHERE measurement_key = ? "
                        + "ORDER BY version DESC LIMIT 1 FOR UPDATE", MAPPER, key)
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
     * 某业务键当前最大版本号；无记录返回 0。
     */
    public int maxVersion(String key) {
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM measurement WHERE measurement_key = ?",
                Integer.class, key);
        return version == null ? 0 : version;
    }

    /**
     * 将旧版本置为已过期（须在持有行锁的事务内调用）。
     */
    public void markSuperseded(long id) {
        jdbc.update("UPDATE measurement SET status = ? WHERE id = ? AND status = 'PENDING'",
                MeasurementStatus.SUPERSEDED.name(), id);
    }

    /**
     * 将测量记录置为已放行（须在持有行锁的事务内调用）。
     */
    public void markReleased(long id) {
        jdbc.update("UPDATE measurement SET status = ? WHERE id = ?",
                MeasurementStatus.RELEASED.name(), id);
    }

    /**
     * 查询当前可用结果：每个业务键仅最新版本、已放行且证书未撤销。
     * instrumentId 为 null 时不过滤仪器；按测量行 ID 升序，排序稳定。
     */
    public List<Measurement> findUsable(String instrumentId) {
        String base = "SELECT m.* FROM measurement m "
                + "JOIN (SELECT measurement_key, MAX(version) AS max_version "
                + "      FROM measurement GROUP BY measurement_key) latest "
                + "  ON latest.measurement_key = m.measurement_key AND latest.max_version = m.version "
                + "JOIN calibration_certificate c ON c.id = m.certificate_id "
                + "WHERE m.status = 'RELEASED' AND c.revoked = FALSE ";
        if (instrumentId == null) {
            return jdbc.query(base + "ORDER BY m.id", MAPPER);
        }
        return jdbc.query(base + "AND m.instrument_id = ? ORDER BY m.id", MAPPER, instrumentId);
    }

    /**
     * 列出某业务键全部版本（版本号升序），用于版本历史查询。
     */
    public List<Measurement> findVersionsByKey(String key) {
        return jdbc.query("SELECT * FROM measurement WHERE measurement_key = ? ORDER BY version",
                MAPPER, key);
    }
}
