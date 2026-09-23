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
 * 测量记录持久化。measurement_key 全局唯一作为幂等键；revision_of 唯一保证每个测量只有单一后继修订；
 * 状态流转由放行、复核、修订、重新放行事务在行锁内完成。
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
            rs.getInt("version"),
            (Long) rs.getObject("revision_of"),
            (Long) rs.getObject("root_id"),
            rs.getString("note"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public MeasurementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入测量记录并返回生成的 ID；measurement_key 冲突时抛出 DuplicateKeyException。
     * 适用于原始提交（version=1、revision_of/root_id 为空）与后继修订。
     */
    public long insert(Measurement measurement) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement "
                            + "(measurement_key, instrument_id, measured_at, raw_reading, lower_limit, upper_limit, "
                            + "submitted_by, certificate_id, computed_value, passed, status, version, "
                            + "revision_of, root_id, note, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
            ps.setInt(12, measurement.version());
            if (measurement.revisionOf() == null) {
                ps.setObject(13, null);
            } else {
                ps.setLong(13, measurement.revisionOf());
            }
            if (measurement.rootId() == null) {
                ps.setObject(14, null);
            } else {
                ps.setLong(14, measurement.rootId());
            }
            ps.setString(15, measurement.note());
            ps.setObject(16, JdbcTimes.toDb(measurement.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 回填修订链根测量 ID（原始提交插入后置为自身）。
     */
    public void updateRootId(long id, long rootId) {
        jdbc.update("UPDATE measurement SET root_id = ? WHERE id = ?", rootId, id);
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
     * 按 ID 查询（不加锁）。
     */
    public Optional<Measurement> findById(long id) {
        return jdbc.query("SELECT * FROM measurement WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行锁（须在事务内调用）。
     */
    public Optional<Measurement> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM measurement WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 将测量记录置为已放行（须在持有行锁的事务内调用）。
     */
    public void markReleased(long id) {
        updateStatus(id, MeasurementStatus.RELEASED);
    }

    /**
     * 将测量记录置为复核驳回（须在持有行锁的事务内调用）。
     */
    public void markRejected(long id) {
        updateStatus(id, MeasurementStatus.REJECTED);
    }

    private void updateStatus(long id, MeasurementStatus status) {
        jdbc.update("UPDATE measurement SET status = ? WHERE id = ?", status.name(), id);
    }

    /**
     * 查询某测量的唯一后继修订（revision_of 唯一）；不存在返回空。
     */
    public Optional<Measurement> findRevisionOf(long predecessorId) {
        return jdbc.query("SELECT * FROM measurement WHERE revision_of = ?", MAPPER, predecessorId)
                .stream().findFirst();
    }

    /**
     * 沿 revision_of 指针回溯某修订链的最新后继（单链，每节点至多一条后继）。
     */
    public Optional<Measurement> findLatestRevision(long rootId) {
        Optional<Measurement> latest = Optional.empty();
        long predecessor = rootId;
        while (true) {
            Optional<Measurement> next = findRevisionOf(predecessor);
            if (next.isEmpty()) {
                return latest;
            }
            latest = next;
            predecessor = next.get().id();
        }
    }

    /**
     * 查询修订链全部测量（含原始），按版本顺序。
     */
    public List<Measurement> findChainByRootId(long rootId) {
        return jdbc.query("SELECT * FROM measurement WHERE id = ? OR root_id = ? ORDER BY id",
                MAPPER, rootId, rootId);
    }

    /**
     * 判断单条测量当前是否对外可用：已放行、被某个 RELEASED 状态批次引用且证书未撤销。
     */
    public boolean isUsable(long measurementId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement m "
                        + "JOIN calibration_certificate c ON c.id = m.certificate_id "
                        + "JOIN release_record r ON r.measurement_id = m.id "
                        + "JOIN release_batch b ON b.batch_id = r.batch_id "
                        + "WHERE m.id = ? AND m.status = 'RELEASED' "
                        + "AND c.revoked = FALSE AND b.status = 'RELEASED'",
                Integer.class, measurementId);
        return count != null && count > 0;
    }

    /**
     * 查询当前可用结果：已放行、所属最新批次处于 RELEASED 且证书未撤销。
     * instrumentId 为 null 时不过滤仪器。重新放行复用原测量行时，其在旧批次为
     * REVIEW_REQUIRED、新批次为 RELEASED，故以 EXISTS 命中可用批次为准。
     */
    public List<Measurement> findUsable(String instrumentId) {
        String base = "SELECT m.* FROM measurement m "
                + "JOIN calibration_certificate c ON c.id = m.certificate_id "
                + "JOIN release_record r ON r.measurement_id = m.id "
                + "JOIN release_batch b ON b.batch_id = r.batch_id "
                + "WHERE m.status = 'RELEASED' AND c.revoked = FALSE AND b.status = 'RELEASED' ";
        String suffix = "GROUP BY m.id ORDER BY m.id";
        if (instrumentId == null) {
            return jdbc.query(base + suffix, MAPPER);
        }
        return jdbc.query(base + "AND m.instrument_id = ? " + suffix, MAPPER, instrumentId);
    }
}
