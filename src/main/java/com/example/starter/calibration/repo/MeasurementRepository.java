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
 * 测量记录持久化。每个 measurement_key 拥有从 1 起递增的多个不可变版本，
 * {@code measurement_latest} 表维护每键唯一最新指针；状态流转由放行事务在指针行锁内完成。
 */
@Repository
public class MeasurementRepository {

    private static final String COLUMNS =
            "id, measurement_key, revision, instrument_id, measured_at, raw_reading, lower_limit, "
                    + "upper_limit, submitted_by, certificate_id, computed_value, passed, status, "
                    + "revision_reason, request_id, revised_by, revised_at, created_at";

    private static final String M_COLUMNS =
            "m.id, m.measurement_key, m.revision, m.instrument_id, m.measured_at, m.raw_reading, "
                    + "m.lower_limit, m.upper_limit, m.submitted_by, m.certificate_id, m.computed_value, "
                    + "m.passed, m.status, m.revision_reason, m.request_id, m.revised_by, m.revised_at, "
                    + "m.created_at";

    private static final RowMapper<Measurement> MAPPER = (rs, rowNum) -> new Measurement(
            rs.getLong("id"),
            rs.getString("measurement_key"),
            rs.getInt("revision"),
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
            rs.getString("revision_reason"),
            rs.getString("request_id"),
            rs.getString("revised_by"),
            JdbcTimes.fromDb(rs.getObject("revised_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public MeasurementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一个测量版本并返回生成的 ID；(measurement_key, revision) 冲突时抛出 DuplicateKeyException。
     */
    public long insert(Measurement measurement) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement "
                            + "(measurement_key, revision, instrument_id, measured_at, raw_reading, "
                            + "lower_limit, upper_limit, submitted_by, certificate_id, computed_value, "
                            + "passed, status, revision_reason, request_id, revised_by, revised_at, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, measurement.measurementKey());
            ps.setInt(2, measurement.revision());
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
            ps.setString(13, measurement.revisionReason());
            ps.setString(14, measurement.requestId());
            ps.setString(15, measurement.revisedBy());
            ps.setObject(16, JdbcTimes.toDb(measurement.revisedAt()));
            ps.setObject(17, JdbcTimes.toDb(measurement.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按记录 ID 查询某个具体版本（不加锁）。
     */
    public Optional<Measurement> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM measurement WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按测量键与版本号查询某个具体版本（不加锁）。
     */
    public Optional<Measurement> findByKeyAndRevision(String key, int revision) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM measurement WHERE measurement_key = ? AND revision = ?",
                MAPPER, key, revision).stream().findFirst();
    }

    /**
     * 按测量键查询最新指针指向的版本（不加锁）；无此键返回 empty。
     */
    public Optional<Measurement> findLatestByKey(String key) {
        return jdbc.query(
                "SELECT " + M_COLUMNS + " FROM measurement m "
                        + "JOIN measurement_latest l ON l.measurement_id = m.id "
                        + "WHERE l.measurement_key = ?",
                MAPPER, key).stream().findFirst();
    }

    /**
     * 查询某测量键的全部版本（按版本号升序）。
     */
    public List<Measurement> findRevisions(String key) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM measurement WHERE measurement_key = ? ORDER BY revision",
                MAPPER, key);
    }

    /**
     * 锁定某测量键的最新指针行并返回其指向的版本（须在事务内调用）。
     * 先以单表 SELECT ... FOR UPDATE 锁定指针行（保证 H2/MySQL 下都串行化同键修订与放行），
     * 再按指针中的记录 ID 读取版本；指针行不存在（键不存在）时返回 empty。
     */
    public Optional<Measurement> findLatestByKeyForUpdate(String key) {
        Long measurementId = jdbc.query(
                "SELECT measurement_id FROM measurement_latest WHERE measurement_key = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong("measurement_id"), key).stream().findFirst().orElse(null);
        if (measurementId == null) {
            return Optional.empty();
        }
        return findById(measurementId);
    }

    /**
     * 初始化最新指针（首次提交）。键冲突时抛出 DuplicateKeyException，由调用方转为幂等/冲突语义。
     */
    public void insertLatest(String key, long measurementId, int revision, java.time.Instant updatedAt) {
        jdbc.update("INSERT INTO measurement_latest (measurement_key, measurement_id, revision, updated_at) "
                        + "VALUES (?, ?, ?, ?)",
                key, measurementId, revision, JdbcTimes.toDb(updatedAt));
    }

    /**
     * 将最新指针切换到新版本（须在持有指针行锁的事务内调用）。
     */
    public void updateLatest(String key, long measurementId, int revision, java.time.Instant updatedAt) {
        jdbc.update("UPDATE measurement_latest SET measurement_id = ?, revision = ?, updated_at = ? "
                        + "WHERE measurement_key = ?",
                measurementId, revision, JdbcTimes.toDb(updatedAt), key);
    }

    /**
     * 将具体版本置为已放行（须在持有该键指针行锁的事务内调用）。
     */
    public void markReleased(long id) {
        jdbc.update("UPDATE measurement SET status = ? WHERE id = ?",
                MeasurementStatus.RELEASED.name(), id);
    }

    /**
     * 查询当前可用结果：每键至多一条最新版本，且该版本已放行、证书未撤销。
     * instrumentId 为 null 时不过滤仪器。
     */
    public List<Measurement> findUsable(String instrumentId) {
        String base = "SELECT " + M_COLUMNS + " FROM measurement_latest l "
                + "JOIN measurement m ON m.id = l.measurement_id "
                + "JOIN calibration_certificate c ON c.id = m.certificate_id "
                + "WHERE m.status = 'RELEASED' AND c.revoked = FALSE ";
        if (instrumentId == null) {
            return jdbc.query(base + "ORDER BY m.id", MAPPER);
        }
        return jdbc.query(base + "AND m.instrument_id = ? ORDER BY m.id", MAPPER, instrumentId);
    }
}
