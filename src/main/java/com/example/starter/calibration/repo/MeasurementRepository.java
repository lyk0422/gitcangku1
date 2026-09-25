package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
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
 * 测量记录持久化（按版本不可变）。measurement_head 保存每个测量键的当前版本指针；
 * 提交/修订/放行均在 head 行锁内串行化状态流转，修订插入新版本并将旧版本置为 SUPERSEDED。
 */
@Repository
public class MeasurementRepository {

    private static final RowMapper<Measurement> MAPPER = (rs, rowNum) -> new Measurement(
            rs.getLong("id"),
            rs.getString("measurement_key"),
            rs.getInt("version"),
            rs.getString("instrument_id"),
            JdbcTimes.fromDb(rs.getObject("measured_at", java.time.LocalDateTime.class)),
            rs.getBigDecimal("raw_reading"),
            rs.getBigDecimal("lower_limit"),
            rs.getBigDecimal("upper_limit"),
            rs.getString("submitted_by"),
            rs.getLong("certificate_id"),
            rs.getBigDecimal("computed_value"),
            rs.getBoolean("passed"),
            MeasurementStatus.valueOf(rs.getString("status")),
            JdbcTimes.fromDb(rs.getObject("created_at", java.time.LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public MeasurementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一个测量版本并返回生成的行 ID。
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
     * 初始化当前版本指针（首次提交时调用）。
     */
    public void insertHead(String key, int version, long id, Instant updatedAt) {
        jdbc.update("INSERT INTO measurement_head (measurement_key, current_version, current_id, updated_at) "
                        + "VALUES (?, ?, ?, ?)",
                key, version, id, JdbcTimes.toDb(updatedAt));
    }

    /**
     * 推进当前版本指针（修订提交后调用，须在 head 行锁内）。
     */
    public void updateHead(String key, int version, long id, Instant updatedAt) {
        jdbc.update("UPDATE measurement_head SET current_version = ?, current_id = ?, updated_at = ? "
                        + "WHERE measurement_key = ?",
                version, id, JdbcTimes.toDb(updatedAt), key);
    }

    /**
     * 锁定测量当前版本指针行（须在事务内调用），串行化修订/放行/复核。
     */
    public void lockHead(String key) {
        jdbc.queryForObject("SELECT measurement_key FROM measurement_head WHERE measurement_key = ? FOR UPDATE",
                String.class, key);
    }

    /**
     * 按测量键查询当前版本（不加锁）。
     */
    public Optional<Measurement> findByKey(String key) {
        return jdbc.query(
                "SELECT m.* FROM measurement m "
                        + "JOIN measurement_head h ON h.current_id = m.id "
                        + "WHERE h.measurement_key = ?", MAPPER, key)
                .stream().findFirst();
    }

    /**
     * 按测量键查询当前版本，并对 head 指针行与版本行加锁（须在事务内调用）。
     */
    public Optional<Measurement> findByKeyForUpdate(String key) {
        List<HeadRow> heads = jdbc.query(
                "SELECT measurement_key, current_version, current_id FROM measurement_head "
                        + "WHERE measurement_key = ? FOR UPDATE",
                (rs, n) -> new HeadRow(rs.getString(1), rs.getInt(2), rs.getLong(3)), key);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        long currentId = heads.get(0).currentId();
        return jdbc.query("SELECT * FROM measurement WHERE id = ? FOR UPDATE", MAPPER, currentId)
                .stream().findFirst();
    }

    /**
     * 按版本行 ID 查询（不加锁）。
     */
    public Optional<Measurement> findById(long id) {
        return jdbc.query("SELECT * FROM measurement WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按版本行 ID 查询并加行锁（须在事务内调用）。
     */
    public Optional<Measurement> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM measurement WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 更新某版本状态（须在持有行锁的事务内调用）。
     */
    public void updateStatus(long id, MeasurementStatus status) {
        jdbc.update("UPDATE measurement SET status = ? WHERE id = ?", status.name(), id);
    }

    /**
     * 查询某测量键的全部版本（按版本号升序）。
     */
    public List<Measurement> findVersions(String key) {
        return jdbc.query("SELECT * FROM measurement WHERE measurement_key = ? ORDER BY version",
                MAPPER, key);
    }

    /**
     * 查询当前可用结果：当前版本已放行且证书未撤销。instrumentId 为 null 时不过滤仪器。
     */
    public List<Measurement> findUsable(String instrumentId) {
        String base = "SELECT m.* FROM measurement m "
                + "JOIN measurement_head h ON h.current_id = m.id "
                + "JOIN calibration_certificate c ON c.id = m.certificate_id "
                + "WHERE m.status = 'RELEASED' AND c.revoked = FALSE ";
        if (instrumentId == null) {
            return jdbc.query(base + "ORDER BY m.id", MAPPER);
        }
        return jdbc.query(base + "AND m.instrument_id = ? ORDER BY m.id", MAPPER, instrumentId);
    }

    /**
     * 查询待复核清单的当前版本：状态为 PENDING 且当前版本尚无有效 PASS 复核。
     * RETURNED（待修订）不属于待复核队列，修订产生新版本后重新进入。
     * instrumentId 为 null 时不过滤仪器。
     */
    public List<Measurement> findPendingReview(String instrumentId) {
        String base = "SELECT m.* FROM measurement m "
                + "JOIN measurement_head h ON h.current_id = m.id "
                + "WHERE m.status = 'PENDING' "
                + "AND NOT EXISTS (SELECT 1 FROM peer_review r WHERE r.measurement_key = m.measurement_key "
                + "AND r.version = m.version AND r.state = 'VALID' AND r.conclusion = 'PASS') ";
        if (instrumentId == null) {
            return jdbc.query(base + "ORDER BY m.id", MAPPER);
        }
        return jdbc.query(base + "AND m.instrument_id = ? ORDER BY m.id", MAPPER, instrumentId);
    }

    private record HeadRow(String measurementKey, int currentVersion, long currentId) {
    }
}
