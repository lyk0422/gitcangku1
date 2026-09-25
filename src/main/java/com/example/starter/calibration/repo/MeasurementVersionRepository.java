package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.MeasurementVersion;

/**
 * 测量版本历史持久化。版本只增不改；重算失败时事务回滚，旧版本仍是当前有效版本。
 */
@Repository
public class MeasurementVersionRepository {

    private static final RowMapper<MeasurementVersion> MAPPER = (rs, rowNum) -> new MeasurementVersion(
            rs.getLong("id"),
            rs.getLong("measurement_id"),
            rs.getInt("version"),
            rs.getLong("certificate_id"),
            rs.getString("cert_version"),
            rs.getBigDecimal("compensation_coeff"),
            rs.getString("uncertainty_version"),
            rs.getBigDecimal("computed_value"),
            rs.getBigDecimal("uncertainty"),
            rs.getBoolean("passed"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public MeasurementVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个测量版本。
     */
    public void insert(long measurementId, int version, Certificate cert,
                       java.math.BigDecimal computedValue, java.math.BigDecimal uncertainty,
                       boolean passed, Instant createdAt) {
        jdbc.update("INSERT INTO measurement_version "
                        + "(measurement_id, version, certificate_id, cert_version, compensation_coeff, "
                        + "uncertainty_version, computed_value, uncertainty, passed, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                measurementId, version, cert.id(), cert.certVersion(), cert.compensationCoeff(),
                cert.uncertaintyVersion(), computedValue, uncertainty, passed, JdbcTimes.toDb(createdAt));
    }

    /**
     * 查询某测量记录的全部版本（按版本号升序），用于血缘追溯。
     */
    public List<MeasurementVersion> findByMeasurementId(long measurementId) {
        return jdbc.query("SELECT * FROM measurement_version WHERE measurement_id = ? ORDER BY version",
                MAPPER, measurementId);
    }
}
