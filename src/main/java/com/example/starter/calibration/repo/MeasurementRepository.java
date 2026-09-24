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
            rs.getBoolean("suspect"),
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
                            + "submitted_by, certificate_id, computed_value, passed, status, suspect, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?)",
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
     * 按主键查询（不加锁）。
     */
    public Optional<Measurement> findById(long id) {
        return jdbc.query("SELECT * FROM measurement WHERE id = ?", MAPPER, id)
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
     * 查询当前可用结果：已放行、未被期间核查隔离（suspect=FALSE）且证书未撤销。
     * instrumentId 为 null 时不过滤仪器。
     */
    public List<Measurement> findUsable(String instrumentId) {
        String base = "SELECT m.* FROM measurement m "
                + "JOIN calibration_certificate c ON c.id = m.certificate_id "
                + "WHERE m.status = 'RELEASED' AND m.suspect = FALSE AND c.revoked = FALSE ";
        if (instrumentId == null) {
            return jdbc.query(base + "ORDER BY m.id", MAPPER);
        }
        return jdbc.query(base + "AND m.instrument_id = ? ORDER BY m.id", MAPPER, instrumentId);
    }

    /**
     * 查询区间 [from, to) 内全部已放行测量记录 ID（含已 SUSPECT 者，按 id 升序）。
     * 用于为本次 FAIL 写入逐结果隔离标记，保证结果同时被多个 FAIL 覆盖时不会提前恢复。
     */
    public List<Long> findReleasedIdsInInterval(String instrumentId,
                                                java.time.Instant from, java.time.Instant to) {
        return jdbc.queryForList(
                "SELECT id FROM measurement "
                        + "WHERE instrument_id = ? AND measured_at >= ? AND measured_at < ? "
                        + "AND status = 'RELEASED' ORDER BY id",
                Long.class, instrumentId, JdbcTimes.toDb(from), JdbcTimes.toDb(to));
    }

    /**
     * 将区间 [from, to) 内尚未隔离的已放行测量结果原子标记为 SUSPECT（须在事务内调用）。
     */
    public void markSuspectInInterval(String instrumentId,
                                      java.time.Instant from, java.time.Instant to) {
        jdbc.update("UPDATE measurement SET suspect = TRUE "
                        + "WHERE instrument_id = ? AND measured_at >= ? AND measured_at < ? "
                        + "AND status = 'RELEASED' AND suspect = FALSE",
                instrumentId, JdbcTimes.toDb(from), JdbcTimes.toDb(to));
    }

    /**
     * 查询区间 [from, to) 内当前处于待放行的测量键（含已 SUSPECT 者），用于放行拦截。
     */
    public List<String> findPendingKeysInInterval(String instrumentId,
                                                  java.time.Instant from, java.time.Instant to) {
        return jdbc.queryForList(
                "SELECT measurement_key FROM measurement "
                        + "WHERE instrument_id = ? AND measured_at >= ? AND measured_at < ? "
                        + "AND status = 'PENDING' ORDER BY id",
                String.class, instrumentId, JdbcTimes.toDb(from), JdbcTimes.toDb(to));
    }

    /**
     * 查询该仪器最早测量时刻；无测量记录时返回空。
     */
    public java.util.Optional<java.time.Instant> findEarliestMeasuredAt(String instrumentId) {
        java.time.LocalDateTime value = jdbc.queryForObject(
                "SELECT MIN(measured_at) FROM measurement WHERE instrument_id = ?",
                java.time.LocalDateTime.class, instrumentId);
        return java.util.Optional.ofNullable(value).map(JdbcTimes::fromDb);
    }

    /**
     * 依据隔离标记清除结果恢复 SUSPECT：仅当该测量不再被任何未清除标记覆盖时置 suspect=FALSE。
     * 在一个事务内随解除操作调用，保证不会恢复仍被其他未解除 FAIL 覆盖的结果。
     */
    public void clearSuspectIfNoOpenMarker(long measurementId) {
        jdbc.update("UPDATE measurement m SET suspect = FALSE WHERE m.id = ? AND NOT EXISTS ("
                + "SELECT 1 FROM measurement_suspect s WHERE s.measurement_id = m.id AND s.cleared = FALSE)",
                measurementId);
    }

    /**
     * 按测量记录 ID 批量查询业务键（按 id 升序）。
     */
    public List<String> findKeysByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
        return jdbc.queryForList(
                "SELECT measurement_key FROM measurement WHERE id IN (" + placeholders + ") ORDER BY id",
                String.class, ids.toArray());
    }
}
