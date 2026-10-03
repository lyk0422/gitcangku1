package com.example.starter.calibration;

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

/**
 * 校准记录访问。
 */
@Repository
public class CalibrationRepository {

    private static final String COLUMNS =
            "id, antenna_id, calibrated_at, azimuth_deviation_deg, elevation_deviation_deg,"
            + " passed, valid_from, valid_until, status, invalidated_at, invalidate_reason";

    private static final RowMapper<AntennaCalibration> MAPPER = (rs, n) -> new AntennaCalibration(
            rs.getLong("id"),
            rs.getLong("antenna_id"),
            JdbcTimes.toInstant(rs, "calibrated_at"),
            rs.getBigDecimal("azimuth_deviation_deg"),
            rs.getBigDecimal("elevation_deviation_deg"),
            rs.getBoolean("passed"),
            JdbcTimes.toInstant(rs, "valid_from"),
            JdbcTimes.toInstant(rs, "valid_until"),
            CalibrationStatus.valueOf(rs.getString("status")),
            JdbcTimes.toInstant(rs, "invalidated_at"),
            rs.getString("invalidate_reason"));

    private final JdbcTemplate jdbc;

    public CalibrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入校准记录并返回生成的校准编号；createdAt 为记录创建时刻（UTC）。 */
    public long insert(AntennaCalibration c, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO antenna_calibration (antenna_id, calibrated_at, azimuth_deviation_deg,"
                    + " elevation_deviation_deg, passed, valid_from, valid_until, status, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, c.antennaId());
            ps.setTimestamp(2, JdbcTimes.toParam(c.calibratedAt()));
            ps.setBigDecimal(3, c.azimuthDeviationDeg());
            ps.setBigDecimal(4, c.elevationDeviationDeg());
            ps.setBoolean(5, c.passed());
            ps.setTimestamp(6, JdbcTimes.toParam(c.validFrom()));
            ps.setTimestamp(7, JdbcTimes.toParam(c.validUntil()));
            ps.setString(8, c.status().name());
            ps.setTimestamp(9, JdbcTimes.toParam(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按编号查询。 */
    public Optional<AntennaCalibration> findById(long id) {
        List<AntennaCalibration> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM antenna_calibration WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 按编号查询并加悲观写锁，用于作废与绑定的并发裁决。 */
    public Optional<AntennaCalibration> findByIdForUpdate(long id) {
        List<AntennaCalibration> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM antenna_calibration WHERE id = ? FOR UPDATE", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 按天线查询校准链，按校准时刻升序。 */
    public List<AntennaCalibration> findByAntennaIdOrderByCalibratedAt(long antennaId) {
        return jdbc.query("SELECT " + COLUMNS
                + " FROM antenna_calibration WHERE antenna_id = ? ORDER BY calibrated_at, id", MAPPER, antennaId);
    }

    /** 标记作废。 */
    public void markInvalidated(long id, Instant invalidatedAt, String reason) {
        jdbc.update("UPDATE antenna_calibration SET status = ?, invalidated_at = ?, invalidate_reason = ?"
                + " WHERE id = ?",
                CalibrationStatus.INVALIDATED.name(), JdbcTimes.toParam(invalidatedAt), reason, id);
    }
}