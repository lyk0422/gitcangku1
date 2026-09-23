package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;

/**
 * 测量记录持久化。measurement_key 全局唯一作为幂等键；状态流转由放行/失效事务在行锁内完成。
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
            (Long) rs.getObject("standard_version_id"),
            rs.getString("impact_version"),
            rs.getString("impact_path"),
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
                            + "submitted_by, certificate_id, computed_value, passed, status, "
                            + "standard_version_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
            if (measurement.standardVersionId() == null) {
                ps.setObject(12, null);
            } else {
                ps.setLong(12, measurement.standardVersionId());
            }
            ps.setObject(13, JdbcTimes.toDb(measurement.createdAt()));
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

    /**
     * 查询失效闭包候选测量：绑定指定标准器版本集合且测量时刻不早于 invalidFrom，按 ID 升序。
     */
    public List<Measurement> findAffectedByStandards(List<Long> standardVersionIds, Instant invalidFrom) {
        StringJoiner placeholders = new StringJoiner(", ");
        standardVersionIds.forEach(id -> placeholders.add("?"));
        Object[] args = new Object[standardVersionIds.size() + 1];
        for (int i = 0; i < standardVersionIds.size(); i++) {
            args[i] = standardVersionIds.get(i);
        }
        args[standardVersionIds.size()] = JdbcTimes.toDb(invalidFrom);
        return jdbc.query("SELECT * FROM measurement WHERE standard_version_id IN (" + placeholders + ") "
                        + "AND measured_at >= ? ORDER BY id",
                MAPPER, args);
    }

    /**
     * 按 ID 集合查询并加行锁（须在事务内调用），按测量键升序加锁，
     * 与批量放行的加锁顺序一致避免死锁；用于失效激活与并发放行按提交顺序互斥。
     */
    public List<Measurement> findByIdsForUpdate(List<Long> ids) {
        StringJoiner placeholders = new StringJoiner(", ");
        ids.forEach(id -> placeholders.add("?"));
        return jdbc.query("SELECT * FROM measurement WHERE id IN (" + placeholders + ") "
                + "ORDER BY measurement_key FOR UPDATE", MAPPER, ids.toArray());
    }

    /**
     * 失效激活：将未审核记录置为 BLOCKED 并记录影响版本号与到失效根的最短血缘路径
     * （须在持有行锁的事务内调用）。
     */
    public void markBlocked(long id, String impactVersion, String impactPath) {
        jdbc.update("UPDATE measurement SET status = ?, impact_version = ?, impact_path = ? WHERE id = ?",
                MeasurementStatus.BLOCKED.name(), impactVersion, impactPath, id);
    }

    /**
     * 失效激活：将已放行结果置为 REVIEW_REQUIRED 并冻结影响版本号与最短血缘路径；
     * 原放行快照（release_record）与计算数值保留（须在持有行锁的事务内调用）。
     */
    public void markReviewRequired(long id, String impactVersion, String impactPath) {
        jdbc.update("UPDATE measurement SET status = ?, impact_version = ?, impact_path = ? WHERE id = ?",
                MeasurementStatus.REVIEW_REQUIRED.name(), impactVersion, impactPath, id);
    }
}
