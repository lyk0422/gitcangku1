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

import com.example.starter.calibration.model.Differential;
import com.example.starter.calibration.model.DifferentialStatus;

/**
 * 差分结果持久化。differential_key 全局唯一；配对占用经 differential_occupation 主键约束，
 * 保证一条测量至多被一条未取消差分占用，取消时删除占用行释放。
 */
@Repository
public class DifferentialRepository {

    private static final RowMapper<Differential> MAPPER = (rs, rowNum) -> new Differential(
            rs.getLong("id"),
            rs.getString("differential_key"),
            rs.getLong("reference_measurement_id"),
            rs.getLong("dut_measurement_id"),
            rs.getString("instrument_id"),
            rs.getLong("reference_certificate_id"),
            rs.getLong("dut_certificate_id"),
            rs.getBigDecimal("difference"),
            rs.getBigDecimal("lower_limit"),
            rs.getBigDecimal("upper_limit"),
            rs.getBoolean("passed"),
            DifferentialStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)),
            rs.getString("released_by"),
            JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)),
            rs.getBigDecimal("frozen_reference_value"),
            rs.getBigDecimal("frozen_dut_value"),
            rs.getString("cancelled_by"),
            JdbcTimes.fromDb(rs.getObject("cancelled_at", LocalDateTime.class)),
            rs.getLong("version"));

    private final JdbcTemplate jdbc;

    public DifferentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入差分记录并返回生成的 ID；differential_key 冲突时抛出 DuplicateKeyException。
     */
    public long insert(Differential d) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO differential "
                            + "(differential_key, reference_measurement_id, dut_measurement_id, instrument_id, "
                            + "reference_certificate_id, dut_certificate_id, difference, lower_limit, upper_limit, "
                            + "passed, status, created_by, created_at, version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, d.differentialKey());
            ps.setLong(2, d.referenceMeasurementId());
            ps.setLong(3, d.dutMeasurementId());
            ps.setString(4, d.instrumentId());
            ps.setLong(5, d.referenceCertificateId());
            ps.setLong(6, d.dutCertificateId());
            ps.setBigDecimal(7, d.difference());
            ps.setBigDecimal(8, d.lowerLimit());
            ps.setBigDecimal(9, d.upperLimit());
            ps.setBoolean(10, d.passed());
            ps.setString(11, d.status().name());
            ps.setString(12, d.createdBy());
            ps.setObject(13, JdbcTimes.toDb(d.createdAt()));
            ps.setLong(14, d.version());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按 ID 查询（不加锁）。 */
    public Optional<Differential> findById(long id) {
        return jdbc.query("SELECT * FROM differential WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 按差分键查询（不加锁）。 */
    public Optional<Differential> findByKey(String key) {
        return jdbc.query("SELECT * FROM differential WHERE differential_key = ?", MAPPER, key)
                .stream().findFirst();
    }

    /** 按差分键查询并加行锁（须在事务内调用），用于放行/取消的状态流转串行化。 */
    public Optional<Differential> findByKeyForUpdate(String key) {
        return jdbc.query("SELECT * FROM differential WHERE differential_key = ? FOR UPDATE", MAPPER, key)
                .stream().findFirst();
    }

    /**
     * 置为已放行：记录放行人/时间，冻结双方未舍入校正值，版本递增（须在持有行锁的事务内调用）。
     */
    public void markReleased(long id, String releasedBy, Instant releasedAt,
                             java.math.BigDecimal frozenReferenceValue, java.math.BigDecimal frozenDutValue) {
        jdbc.update("UPDATE differential SET status = ?, released_by = ?, released_at = ?, "
                        + "frozen_reference_value = ?, frozen_dut_value = ?, version = version + 1 WHERE id = ?",
                DifferentialStatus.RELEASED.name(), releasedBy, JdbcTimes.toDb(releasedAt),
                frozenReferenceValue, frozenDutValue, id);
    }

    /**
     * 置为已取消：记录取消人/时间，版本递增；记录保留不删除（须在持有行锁的事务内调用）。
     */
    public void markCancelled(long id, String cancelledBy, Instant cancelledAt) {
        jdbc.update("UPDATE differential SET status = ?, cancelled_by = ?, cancelled_at = ?, "
                        + "version = version + 1 WHERE id = ?",
                DifferentialStatus.CANCELLED.name(), cancelledBy, JdbcTimes.toDb(cancelledAt), id);
    }

    /**
     * 占用一条测量（须在事务内调用）；measurement_id 主键冲突时抛出 DuplicateKeyException。
     */
    public void insertOccupation(long differentialId, long measurementId) {
        jdbc.update("INSERT INTO differential_occupation (measurement_id, differential_id, created_at) "
                        + "VALUES (?, ?, ?)",
                measurementId, differentialId, JdbcTimes.toDb(Instant.now()));
    }

    /** 释放某差分的全部配对占用（取消时调用）。 */
    public void deleteOccupation(long differentialId) {
        jdbc.update("DELETE FROM differential_occupation WHERE differential_id = ?", differentialId);
    }

    /** 测量是否已被某条未取消差分占用。 */
    public boolean isOccupied(long measurementId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM differential_occupation WHERE measurement_id = ?",
                Integer.class, measurementId);
        return count != null && count > 0;
    }

    /**
     * 查询当前可用差分：已放行且双方证书均未撤销。instrumentId 为 null 时不过滤仪器。
     */
    public List<Differential> findUsable(String instrumentId) {
        String base = "SELECT d.* FROM differential d "
                + "JOIN calibration_certificate rc ON rc.id = d.reference_certificate_id "
                + "JOIN calibration_certificate dc ON dc.id = d.dut_certificate_id "
                + "WHERE d.status = 'RELEASED' AND rc.revoked = FALSE AND dc.revoked = FALSE ";
        if (instrumentId == null) {
            return jdbc.query(base + "ORDER BY d.id", MAPPER);
        }
        return jdbc.query(base + "AND d.instrument_id = ? ORDER BY d.id", MAPPER, instrumentId);
    }
}
