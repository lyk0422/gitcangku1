package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.MeasurementVersion;

/**
 * 测量版本持久化。版本只增：提交生成 v1，重算在事务内追加新版本；
 * 已放行测量不会产生新版本（由服务层状态门禁保证）。
 */
@Repository
public class MeasurementVersionRepository {

    private static final RowMapper<MeasurementVersion> MAPPER = (rs, rowNum) -> new MeasurementVersion(
            rs.getLong("id"),
            rs.getLong("measurement_id"),
            rs.getInt("version_no"),
            rs.getBigDecimal("temperature"),
            rs.getBigDecimal("humidity"),
            rs.getBigDecimal("uncertainty"),
            rs.getLong("certificate_id"),
            (Long) rs.getObject("compensation_profile_id"),
            rs.getBigDecimal("computed_value"),
            rs.getBigDecimal("compensated_value"),
            rs.getBoolean("passed"),
            (Boolean) rs.getObject("compensated_passed"),
            (Long) rs.getObject("parent_version_id"),
            rs.getString("calc_key"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public MeasurementVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个测量版本并返回生成的版本 ID。
     */
    public long insert(MeasurementVersion version) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement_version (measurement_id, version_no, temperature, humidity, uncertainty, "
                            + "certificate_id, compensation_profile_id, computed_value, compensated_value, "
                            + "passed, compensated_passed, parent_version_id, calc_key, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, version.measurementId());
            ps.setInt(2, version.versionNo());
            ps.setBigDecimal(3, version.temperature());
            ps.setBigDecimal(4, version.humidity());
            ps.setBigDecimal(5, version.uncertainty());
            ps.setLong(6, version.certificateId());
            if (version.compensationProfileId() == null) {
                ps.setObject(7, null);
            } else {
                ps.setLong(7, version.compensationProfileId());
            }
            ps.setBigDecimal(8, version.computedValue());
            ps.setBigDecimal(9, version.compensatedValue());
            ps.setBoolean(10, version.passed());
            ps.setObject(11, version.compensatedPassed());
            if (version.parentVersionId() == null) {
                ps.setObject(12, null);
            } else {
                ps.setLong(12, version.parentVersionId());
            }
            ps.setString(13, version.calcKey());
            ps.setObject(14, JdbcTimes.toDb(version.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询某测量的全部版本（按版本号升序），构成重算链。
     */
    public List<MeasurementVersion> findByMeasurementId(long measurementId) {
        return jdbc.query(
                "SELECT * FROM measurement_version WHERE measurement_id = ? ORDER BY version_no",
                MAPPER, measurementId);
    }
}
