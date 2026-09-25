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

import com.example.starter.calibration.model.MeasurementVersion;

/**
 * 测量版本血缘持久化。提交与每次重算各追加一行，只增不改；
 * (measurement_id, version_no) 与 reference_key 均唯一，后者支撑指纹幂等。
 */
@Repository
public class MeasurementVersionRepository {

    private static final RowMapper<MeasurementVersion> MAPPER = (rs, rowNum) -> new MeasurementVersion(
            rs.getLong("id"),
            rs.getLong("measurement_id"),
            rs.getInt("version_no"),
            rs.getLong("certificate_id"),
            rs.getString("standard_id"),
            rs.getString("certificate_version"),
            rs.getBigDecimal("coeff_a"),
            rs.getBigDecimal("offset_b"),
            rs.getBigDecimal("uncertainty"),
            rs.getString("uncertainty_version"),
            rs.getBigDecimal("computed_value"),
            rs.getBigDecimal("expanded_uncertainty"),
            rs.getBoolean("passed"),
            rs.getString("reference_key"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public MeasurementVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个测量版本并返回生成的行 ID。
     * 版本号冲突或 reference_key 冲突时抛出 DuplicateKeyException。
     */
    public long append(MeasurementVersion version) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement_version "
                            + "(measurement_id, version_no, certificate_id, standard_id, certificate_version, "
                            + "coeff_a, offset_b, uncertainty, uncertainty_version, computed_value, "
                            + "expanded_uncertainty, passed, reference_key, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, version.measurementId());
            ps.setInt(2, version.versionNo());
            ps.setLong(3, version.certificateId());
            ps.setString(4, version.standardId());
            ps.setString(5, version.certificateVersion());
            ps.setBigDecimal(6, version.a());
            ps.setBigDecimal(7, version.b());
            ps.setBigDecimal(8, version.uncertainty());
            ps.setString(9, version.uncertaintyVersion());
            ps.setBigDecimal(10, version.computedValue());
            ps.setBigDecimal(11, version.expandedUncertainty());
            ps.setBoolean(12, version.passed());
            ps.setString(13, version.referenceKey());
            ps.setObject(14, JdbcTimes.toDb(version.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询某测量的全部版本血缘（按版本号升序）。
     */
    public List<MeasurementVersion> findByMeasurementId(long measurementId) {
        return jdbc.query("SELECT * FROM measurement_version WHERE measurement_id = ? ORDER BY version_no",
                MAPPER, measurementId);
    }

    /**
     * 按 (测量, 版本号) 查询特定版本。
     */
    public Optional<MeasurementVersion> findByMeasurementAndNo(long measurementId, int versionNo) {
        return jdbc.query(
                "SELECT * FROM measurement_version WHERE measurement_id = ? AND version_no = ?",
                MAPPER, measurementId, versionNo).stream().findFirst();
    }
}
