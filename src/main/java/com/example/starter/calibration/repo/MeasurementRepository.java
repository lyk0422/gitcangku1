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
 * 测量记录持久化。measurement_key 全局唯一作为幂等键；状态流转由放行事务在行锁内完成。
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
            rs.getBigDecimal("computed_value"),
            rs.getBoolean("passed"),
            MeasurementStatus.valueOf(rs.getString("status")),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

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
                            + "submitted_by, certificate_id, computed_value, passed, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, measurement.measurementKey());
            ps.setString(2, measurement.instrumentId());
            ps.setObject(3, JdbcTimes.toDb(measurement.measuredAt()));
            ps.setBigDecimal(4, measurement.rawReading());
            ps.setBigDecimal(5, measurement.lowerLimit());
            ps.setBigDecimal(6, measurement.upperLimit());
            ps.setString(7, measurement.submittedBy());
            ps.setLong(8, measurement.certificateId());
            ps.setBigDecimal(9, measurement.computedValue());
            ps.setBoolean(10, measurement.passed());
            ps.setString(11, measurement.status().name());
            ps.setObject(12, JdbcTimes.toDb(measurement.createdAt()));
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
     * 按测量键批量查询并加行锁，统一按测量 ID 升序加锁（须在事务内调用）。
     * 与 FAIL 核查区间加锁顺序一致，避免并发事务间死锁。
     */
    public List<Measurement> findByKeysForUpdateOrderedById(List<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(keys.size(), "?"));
        return jdbc.query(
                "SELECT * FROM measurement WHERE measurement_key IN (" + placeholders + ") ORDER BY id FOR UPDATE",
                MAPPER, keys.toArray());
    }

    /**
     * 将测量记录置为已放行（须在持有行锁的事务内调用）。
     */
    public void markReleased(long id) {
        jdbc.update("UPDATE measurement SET status = ? WHERE id = ?",
                MeasurementStatus.RELEASED.name(), id);
    }

    /**
     * 查询该仪器最早测量时刻；无测量记录时为空。用于 FAIL 追溯区间此前无 PASS 时确定起点。
     */
    public Optional<java.time.Instant> findEarliestMeasuredAt(String instrumentId) {
        List<java.time.LocalDateTime> values = jdbc.query(
                "SELECT MIN(measured_at) FROM measurement WHERE instrument_id = ?",
                (rs, rowNum) -> rs.getObject(1, java.time.LocalDateTime.class), instrumentId);
        return values.isEmpty() || values.get(0) == null
                ? Optional.empty()
                : Optional.of(JdbcTimes.fromDb(values.get(0)));
    }

    /**
     * 锁定区间 [from, to) 内该仪器全部测量记录并返回（须在事务内调用）。
     * 行锁使 FAIL 标记与区间内放行按事务提交顺序裁决。
     */
    public List<Measurement> findInRangeForUpdate(String instrumentId,
                                                  java.time.Instant from, java.time.Instant to) {
        return jdbc.query(
                "SELECT * FROM measurement WHERE instrument_id = ? AND measured_at >= ? AND measured_at < ? "
                        + "ORDER BY id FOR UPDATE",
                MAPPER, instrumentId, JdbcTimes.toDb(from), JdbcTimes.toDb(to));
    }

    /**
     * 查询区间 [from, to) 内该仪器全部测量记录（不加锁），按 ID 升序。
     */
    public List<Measurement> findInRange(String instrumentId,
                                         java.time.Instant from, java.time.Instant to) {
        return jdbc.query(
                "SELECT * FROM measurement WHERE instrument_id = ? AND measured_at >= ? AND measured_at < ? "
                        + "ORDER BY id",
                MAPPER, instrumentId, JdbcTimes.toDb(from), JdbcTimes.toDb(to));
    }

    /**
     * 查询当前可用结果：已放行、证书未撤销且不存在未解除 SUSPECT 标记。
     * instrumentId 为 null 时不过滤仪器。
     */
    public List<Measurement> findUsable(String instrumentId) {
        String base = "SELECT m.* FROM measurement m "
                + "JOIN calibration_certificate c ON c.id = m.certificate_id "
                + "WHERE m.status = 'RELEASED' AND c.revoked = FALSE "
                + "AND NOT EXISTS (SELECT 1 FROM suspect_marking s "
                + "WHERE s.measurement_id = m.id AND s.cleared_by_check_id IS NULL) ";
        if (instrumentId == null) {
            return jdbc.query(base + "ORDER BY m.id", MAPPER);
        }
        return jdbc.query(base + "AND m.instrument_id = ? ORDER BY m.id", MAPPER, instrumentId);
    }
}
