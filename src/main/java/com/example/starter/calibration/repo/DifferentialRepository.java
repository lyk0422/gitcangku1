package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.Differential;
import com.example.starter.calibration.model.DifferentialStatus;

/**
 * 差分记录持久化。占用表 differential_occupation 以 measurement_id 主键保证
 * 一条测量同一时间最多被一条未取消差分占用；取消时删除占用行释放测量。
 */
@Repository
public class DifferentialRepository {

    private static final RowMapper<Differential> MAPPER = (rs, rowNum) -> new Differential(
            rs.getLong("id"),
            rs.getString("instrument_id"),
            rs.getLong("reference_measurement_id"),
            rs.getLong("dut_measurement_id"),
            rs.getLong("reference_certificate_id"),
            rs.getLong("dut_certificate_id"),
            rs.getBigDecimal("reference_value"),
            rs.getBigDecimal("dut_value"),
            rs.getBigDecimal("diff_value"),
            rs.getBigDecimal("lower_limit"),
            rs.getBigDecimal("upper_limit"),
            rs.getBoolean("passed"),
            DifferentialStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getString("created_by"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)),
            rs.getString("released_by"),
            JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)),
            rs.getString("cancelled_by"),
            JdbcTimes.fromDb(rs.getObject("cancelled_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public DifferentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入差分记录（初始状态 PENDING，版本 1）并返回生成的 ID。
     */
    public long insert(Differential differential) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO differential_pair "
                            + "(instrument_id, reference_measurement_id, dut_measurement_id, "
                            + "reference_certificate_id, dut_certificate_id, "
                            + "reference_value, dut_value, diff_value, lower_limit, upper_limit, "
                            + "passed, status, version, created_by, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, differential.instrumentId());
            ps.setLong(2, differential.referenceMeasurementId());
            ps.setLong(3, differential.dutMeasurementId());
            ps.setLong(4, differential.referenceCertificateId());
            ps.setLong(5, differential.dutCertificateId());
            ps.setBigDecimal(6, differential.referenceValue());
            ps.setBigDecimal(7, differential.dutValue());
            ps.setBigDecimal(8, differential.diffValue());
            ps.setBigDecimal(9, differential.lowerLimit());
            ps.setBigDecimal(10, differential.upperLimit());
            ps.setBoolean(11, differential.passed());
            ps.setString(12, differential.status().name());
            ps.setInt(13, differential.version());
            ps.setString(14, differential.createdBy());
            ps.setObject(15, JdbcTimes.toDb(differential.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 ID 查询（不加锁）。
     */
    public Optional<Differential> findById(long id) {
        return jdbc.query("SELECT * FROM differential_pair WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行锁（须在事务内调用），用于放行/取消时串行化状态流转。
     */
    public Optional<Differential> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM differential_pair WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 将差分记录置为已放行并递增版本号（须在持有行锁的事务内调用）。
     */
    public void markReleased(long id, String releasedBy, Instant releasedAt) {
        jdbc.update("UPDATE differential_pair SET status = ?, released_by = ?, released_at = ?, "
                        + "version = version + 1 WHERE id = ?",
                DifferentialStatus.RELEASED.name(), releasedBy, JdbcTimes.toDb(releasedAt), id);
    }

    /**
     * 将差分记录置为已取消并递增版本号（须在持有行锁的事务内调用）；记录保留不删除。
     */
    public void markCancelled(long id, String cancelledBy, Instant cancelledAt) {
        jdbc.update("UPDATE differential_pair SET status = ?, cancelled_by = ?, cancelled_at = ?, "
                        + "version = version + 1 WHERE id = ?",
                DifferentialStatus.CANCELLED.name(), cancelledBy, JdbcTimes.toDb(cancelledAt), id);
    }

    /**
     * 插入测量占用；measurement_id 冲突时抛出 DuplicateKeyException。
     */
    public void insertOccupation(long measurementId, long differentialId, Instant createdAt) {
        jdbc.update("INSERT INTO differential_occupation (measurement_id, differential_id, created_at) "
                        + "VALUES (?, ?, ?)",
                measurementId, differentialId, JdbcTimes.toDb(createdAt));
    }

    /**
     * 删除某差分记录的全部占用（取消时释放测量）。
     */
    public void deleteOccupations(long differentialId) {
        jdbc.update("DELETE FROM differential_occupation WHERE differential_id = ?", differentialId);
    }

    /**
     * 测量当前是否被未取消的差分占用。
     */
    public boolean isOccupied(long measurementId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM differential_occupation WHERE measurement_id = ?",
                Integer.class, measurementId);
        return count != null && count > 0;
    }
}
