package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
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
 * 失效冻结将未审核记录置为 BLOCKED、已放行结果置为 REVIEW_REQUIRED 并写入影响版本号。
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
            (Long) rs.getObject("standard_version_id"),
            rs.getBigDecimal("computed_value"),
            rs.getBoolean("passed"),
            MeasurementStatus.valueOf(rs.getString("status")),
            rs.getString("impact_version"),
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
                            + "submitted_by, certificate_id, standard_version_id, computed_value, passed, status, "
                            + "impact_version, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, measurement.measurementKey());
            ps.setString(2, measurement.instrumentId());
            ps.setObject(3, JdbcTimes.toDb(measurement.measuredAt()));
            ps.setBigDecimal(4, measurement.rawReading());
            ps.setBigDecimal(5, measurement.lowerLimit());
            ps.setBigDecimal(6, measurement.upperLimit());
            ps.setString(7, measurement.submittedBy());
            ps.setLong(8, measurement.certificateId());
            if (measurement.standardVersionId() == null) {
                ps.setObject(9, null);
            } else {
                ps.setLong(9, measurement.standardVersionId());
            }
            ps.setBigDecimal(10, measurement.computedValue());
            ps.setBoolean(11, measurement.passed());
            ps.setString(12, measurement.status().name());
            ps.setString(13, measurement.impactVersion());
            ps.setObject(14, JdbcTimes.toDb(measurement.createdAt()));
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
     * 按自增 ID 集合查询（不加锁），按 ID 升序返回；空集合返回空列表。
     */
    public List<Measurement> findByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", ids.stream().map(id -> "?").toList());
        return jdbc.query("SELECT * FROM measurement WHERE id IN (" + placeholders + ") ORDER BY id",
                MAPPER, ids.toArray());
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
     * 查询失效闭包命中的测量记录：绑定版本在受影响版本集合内且测量时刻不早于失效起始时刻。
     * 按 ID 升序返回，保证闭包稳定排序。versionIds 为空时返回空列表。
     */
    public List<Measurement> findAffected(List<Long> versionIds, Instant invalidFrom) {
        if (versionIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", versionIds.stream().map(id -> "?").toList());
        return jdbc.query("SELECT * FROM measurement WHERE standard_version_id IN (" + placeholders + ") "
                        + "AND measured_at >= ? ORDER BY id",
                MAPPER, buildArgs(versionIds, invalidFrom));
    }

    /**
     * 失效冻结：更新状态并写入影响版本号（须在失效激活事务内调用）。
     */
    public void markImpacted(long id, MeasurementStatus status, String impactVersion) {
        jdbc.update("UPDATE measurement SET status = ?, impact_version = ? WHERE id = ?",
                status.name(), impactVersion, id);
    }

    /**
     * 按影响版本号查询被冻结的测量记录（按 ID 升序），用于影响查询按 impactVersion 重现。
     */
    public List<Measurement> findByImpactVersion(String impactVersion) {
        return jdbc.query("SELECT * FROM measurement WHERE impact_version = ? ORDER BY id",
                MAPPER, impactVersion);
    }

    private static Object[] buildArgs(List<Long> versionIds, Instant invalidFrom) {
        Object[] args = new Object[versionIds.size() + 1];
        for (int i = 0; i < versionIds.size(); i++) {
            args[i] = versionIds.get(i);
        }
        args[versionIds.size()] = JdbcTimes.toDb(invalidFrom);
        return args;
    }
}
