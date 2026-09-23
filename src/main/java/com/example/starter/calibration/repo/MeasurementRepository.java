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
import com.example.starter.calibration.model.MeasurementHead;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.RevisionRequestRecord;

/**
 * 测量记录持久化。同一 measurementKey 的每个修订版本各占一行；
 * 最新版本由 measurement_head 唯一指针维护，状态流转由放行事务在行锁内完成。
 */
@Repository
public class MeasurementRepository {

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
            rs.getString("revised_by"),
            JdbcTimes.fromDb(rs.getObject("revised_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private static final RowMapper<MeasurementHead> HEAD_MAPPER = (rs, rowNum) -> new MeasurementHead(
            rs.getString("measurement_key"),
            rs.getInt("latest_revision"),
            rs.getLong("latest_measurement_id"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private static final RowMapper<RevisionRequestRecord> IDEMPOTENCY_MAPPER = (rs, rowNum) ->
            new RevisionRequestRecord(
                    rs.getLong("id"),
                    rs.getString("measurement_key"),
                    rs.getString("request_id"),
                    rs.getInt("expected_revision"),
                    rs.getBigDecimal("raw_reading"),
                    rs.getBigDecimal("lower_limit"),
                    rs.getBigDecimal("upper_limit"),
                    rs.getString("reason"),
                    rs.getString("actor"),
                    rs.getInt("resulting_revision"),
                    JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public MeasurementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一个测量版本行并返回生成的 ID；(measurement_key, revision) 冲突时抛出 DuplicateKeyException。
     */
    public long insert(Measurement measurement) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement "
                            + "(measurement_key, revision, instrument_id, measured_at, raw_reading, "
                            + "lower_limit, upper_limit, submitted_by, certificate_id, computed_value, "
                            + "passed, status, revision_reason, revised_by, revised_at, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
            ps.setString(14, measurement.revisedBy());
            ps.setObject(15, JdbcTimes.toDb(measurement.revisedAt()));
            ps.setObject(16, JdbcTimes.toDb(measurement.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 创建最新版本指针（首次提交）；键冲突时抛出 DuplicateKeyException。
     */
    public void insertHead(String key, int revision, long measurementId,
                           java.time.Instant createdAt, java.time.Instant updatedAt) {
        jdbc.update("INSERT INTO measurement_head "
                        + "(measurement_key, latest_revision, latest_measurement_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                key, revision, measurementId, JdbcTimes.toDb(createdAt), JdbcTimes.toDb(updatedAt));
    }

    /**
     * 更新最新版本指针（修订成功）；受影响行数为 0 表示指针不存在。
     */
    public int updateHead(String key, int newRevision, long newMeasurementId, java.time.Instant updatedAt) {
        return jdbc.update("UPDATE measurement_head SET latest_revision = ?, "
                        + "latest_measurement_id = ?, updated_at = ? WHERE measurement_key = ?",
                newRevision, newMeasurementId, JdbcTimes.toDb(updatedAt), key);
    }

    /**
     * 查询最新版本指针（不加锁）。
     */
    public Optional<MeasurementHead> findHead(String key) {
        return jdbc.query("SELECT * FROM measurement_head WHERE measurement_key = ?", HEAD_MAPPER, key)
                .stream().findFirst();
    }

    /**
     * 查询最新版本指针并加行锁（须在事务内调用），用于串行化修订与放行。
     */
    public Optional<MeasurementHead> findHeadForUpdate(String key) {
        return jdbc.query("SELECT * FROM measurement_head WHERE measurement_key = ? FOR UPDATE",
                HEAD_MAPPER, key).stream().findFirst();
    }

    /**
     * 按 (measurement_key, revision) 查询指定版本（不加锁）。
     */
    public Optional<Measurement> findByKeyAndRevision(String key, int revision) {
        return jdbc.query("SELECT * FROM measurement WHERE measurement_key = ? AND revision = ?",
                MAPPER, key, revision).stream().findFirst();
    }

    /**
     * 按 measurement.id 查询并加行锁（须在事务内调用），用于放行时串行化状态流转。
     */
    public Optional<Measurement> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM measurement WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询某测量键的全部版本（按版本号升序）。
     */
    public List<Measurement> findAllByKey(String key) {
        return jdbc.query("SELECT * FROM measurement WHERE measurement_key = ? ORDER BY revision",
                MAPPER, key);
    }

    /**
     * 将测量版本行置为已放行（须在持有行锁的事务内调用）。
     */
    public void markReleased(long id) {
        jdbc.update("UPDATE measurement SET status = ? WHERE id = ?",
                MeasurementStatus.RELEASED.name(), id);
    }

    /**
     * 查询当前可用结果：最新版本、已放行且证书未撤销。instrumentId 为 null 时不过滤仪器。
     * 每键至多返回最新版本一行；旧版即使已放行也不会再出现（h.id = m.id）。
     */
    public List<Measurement> findUsable(String instrumentId) {
        String base = "SELECT m.* FROM measurement m "
                + "JOIN measurement_head h ON h.measurement_key = m.measurement_key AND h.latest_measurement_id = m.id "
                + "JOIN calibration_certificate c ON c.id = m.certificate_id "
                + "WHERE m.status = 'RELEASED' AND c.revoked = FALSE ";
        if (instrumentId == null) {
            return jdbc.query(base + "ORDER BY m.id", MAPPER);
        }
        return jdbc.query(base + "AND m.instrument_id = ? ORDER BY m.id", MAPPER, instrumentId);
    }

    /**
     * 按 (measurement_key, request_id) 查询已成功的修订幂等记录（不加锁）。
     */
    public Optional<RevisionRequestRecord> findRevisionRequest(String key, String requestId) {
        return jdbc.query("SELECT * FROM measurement_revision_request "
                        + "WHERE measurement_key = ? AND request_id = ?",
                IDEMPOTENCY_MAPPER, key, requestId).stream().findFirst();
    }

    /**
     * 写入修订幂等记录；(measurement_key, request_id) 冲突时抛出 DuplicateKeyException。
     */
    public void insertRevisionRequest(RevisionRequestRecord record) {
        jdbc.update("INSERT INTO measurement_revision_request "
                        + "(measurement_key, request_id, expected_revision, raw_reading, lower_limit, "
                        + "upper_limit, reason, actor, resulting_revision, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.measurementKey(), record.requestId(), record.expectedRevision(),
                record.rawReading(), record.lowerLimit(), record.upperLimit(), record.reason(),
                record.actor(), record.resultingRevision(), JdbcTimes.toDb(record.createdAt()));
    }
}
