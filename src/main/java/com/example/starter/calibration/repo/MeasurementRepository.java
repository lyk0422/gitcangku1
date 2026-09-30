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
 * 测量记录持久化。measurement_key 全局唯一作为幂等键；状态流转由放行/裁决事务在行锁内完成。
 */
@Repository
public class MeasurementRepository {

    private static final String COLUMNS =
            "id, measurement_key, instrument_id, measured_at, raw_reading, lower_limit, upper_limit, "
                    + "submitted_by, certificate_id, computed_value, passed, status, "
                    + "batch_id, revision_no, excluded, excluded_at, excluded_by_outlier_id, created_at";

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
            (Long) rs.getObject("batch_id"),
            (Integer) rs.getObject("revision_no"),
            rs.getBoolean("excluded"),
            JdbcTimes.fromDb(rs.getObject("excluded_at", LocalDateTime.class)),
            (Long) rs.getObject("excluded_by_outlier_id"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public MeasurementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入测量记录并返回生成的 ID；measurement_key 冲突时抛出 DuplicateKeyException。
     * 批次/修订为空时写入 NULL。
     */
    public long insert(Measurement measurement) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement "
                            + "(measurement_key, instrument_id, measured_at, raw_reading, lower_limit, upper_limit, "
                            + "submitted_by, certificate_id, computed_value, passed, status, "
                            + "batch_id, revision_no, excluded, excluded_at, excluded_by_outlier_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, NULL, NULL, ?)",
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
            setNullableLong(ps, 12, measurement.batchId());
            setNullableInt(ps, 13, measurement.revisionNo());
            ps.setObject(14, JdbcTimes.toDb(measurement.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    /**
     * 按测量键查询（不加锁）。
     */
    public Optional<Measurement> findByKey(String key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM measurement WHERE measurement_key = ?", MAPPER, key)
                .stream().findFirst();
    }

    /**
     * 按测量键查询并加行锁（须在事务内调用），用于放行时串行化状态流转。
     */
    public Optional<Measurement> findByKeyForUpdate(String key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM measurement WHERE measurement_key = ? FOR UPDATE",
                MAPPER, key).stream().findFirst();
    }

    /** 按 ID 查询（不加锁）。 */
    public Optional<Measurement> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM measurement WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 按 ID 查询并加行锁。 */
    public Optional<Measurement> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM measurement WHERE id = ? FOR UPDATE", MAPPER, id)
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
     * 将离群读数置为已排除（REJECT 裁决事务内调用）：状态 EXCLUDED 并封存排除元数据。
     */
    public void markExcluded(long id, long outlierId, Instant excludedAt) {
        jdbc.update(
                "UPDATE measurement SET status = 'EXCLUDED', excluded = TRUE, excluded_at = ?, "
                        + "excluded_by_outlier_id = ? WHERE id = ?",
                JdbcTimes.toDb(excludedAt), outlierId, id);
    }

    /** 查询批次指定修订下的全部读数，按提交顺序 id 升序（含已排除，明细用）。 */
    public List<Measurement> findByBatchRevision(long batchId, int revisionNo) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM measurement WHERE batch_id = ? AND revision_no = ? ORDER BY id",
                MAPPER, batchId, revisionNo);
    }

    /** 查询批次指定修订下参与统计/放行的读数（未排除），并对行加锁（须在事务内）。 */
    public List<Measurement> findActiveByBatchRevisionForUpdate(long batchId, int revisionNo) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM measurement WHERE batch_id = ? AND revision_no = ? "
                        + "AND excluded = FALSE ORDER BY id FOR UPDATE",
                MAPPER, batchId, revisionNo);
    }

    /**
     * 查询当前可用结果：已放行且证书未撤销。instrumentId 为 null 时不过滤仪器。
     */
    public List<Measurement> findUsable(String instrumentId) {
        String base = "SELECT " + flatColumns() + " FROM measurement m "
                + "JOIN calibration_certificate c ON c.id = m.certificate_id "
                + "WHERE m.status = 'RELEASED' AND c.revoked = FALSE ";
        if (instrumentId == null) {
            return jdbc.query(base + "ORDER BY m.id", MAPPER);
        }
        return jdbc.query(base + "AND m.instrument_id = ? ORDER BY m.id", MAPPER, instrumentId);
    }

    /** findUsable 使用 m.* 会与 JOIN 列冲突，这里显式列出 measurement 列并加表别名前缀。 */
    private static String flatColumns() {
        return ("m.id, m.measurement_key, m.instrument_id, m.measured_at, m.raw_reading, m.lower_limit, "
                + "m.upper_limit, m.submitted_by, m.certificate_id, m.computed_value, m.passed, m.status, "
                + "m.batch_id, m.revision_no, m.excluded, m.excluded_at, m.excluded_by_outlier_id, m.created_at");
    }
}
