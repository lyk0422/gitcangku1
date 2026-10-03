package com.example.starter.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.CalibrationRecord;
import com.example.starter.domain.CalibrationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 校准记录仓库：负责校准写入、按天线查询校准链与作废状态更新。
 */
@Repository
public class CalibrationRepository {

    private static final RowMapper<CalibrationRecord> MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;

    public CalibrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static CalibrationRecord map(ResultSet rs) throws SQLException {
        return new CalibrationRecord(
                rs.getLong("id"),
                rs.getLong("antenna_id"),
                JdbcTimes.read(rs, "calibrated_at"),
                rs.getBigDecimal("azimuth_deviation"),
                rs.getBigDecimal("elevation_deviation"),
                rs.getBoolean("passed"),
                JdbcTimes.read(rs, "valid_until"),
                CalibrationStatus.valueOf(rs.getString("status")),
                JdbcTimes.read(rs, "voided_at"),
                rs.getString("void_reason"),
                JdbcTimes.read(rs, "created_at"));
    }

    /** 插入校准记录并返回自增主键。 */
    public long insert(long antennaId, Instant calibratedAt, java.math.BigDecimal azimuth,
            java.math.BigDecimal elevation, boolean passed, Instant validUntil, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO calibration_record (antenna_id, calibrated_at, azimuth_deviation,"
                            + " elevation_deviation, passed, valid_until, status, created_at)"
                            + " VALUES (?,?,?,?,?,?,'ACTIVE',?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, antennaId);
            ps.setTimestamp(2, JdbcTimes.toSql(calibratedAt));
            ps.setBigDecimal(3, azimuth);
            ps.setBigDecimal(4, elevation);
            ps.setBoolean(5, passed);
            ps.setTimestamp(6, JdbcTimes.toSql(validUntil));
            ps.setTimestamp(7, JdbcTimes.toSql(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<CalibrationRecord> findById(long id) {
        List<CalibrationRecord> rows = jdbc.query(
                "SELECT * FROM calibration_record WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 按天线查询校准链，按校准时刻升序。 */
    public List<CalibrationRecord> findByAntennaId(long antennaId) {
        return jdbc.query(
                "SELECT * FROM calibration_record WHERE antenna_id = ? ORDER BY calibrated_at, id",
                MAPPER, antennaId);
    }

    /** 作废校准记录，返回受影响行数（0 表示已作废或不存在）。 */
    public int markVoided(long id, Instant voidedAt, String reason) {
        return jdbc.update(
                "UPDATE calibration_record SET status = 'VOIDED', voided_at = ?, void_reason = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'",
                JdbcTimes.toSql(voidedAt), reason, id);
    }
}