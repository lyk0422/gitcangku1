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

import com.example.starter.calibration.model.MeasurementRevision;

/**
 * 测量修订历史持久化。修订记录只增不改，排序按自增主键稳定。
 */
@Repository
public class MeasurementRevisionRepository {

    private static final RowMapper<MeasurementRevision> MAPPER = (rs, rowNum) -> new MeasurementRevision(
            rs.getLong("id"),
            rs.getLong("measurement_id"),
            rs.getLong("from_version"),
            rs.getLong("to_version"),
            JdbcTimes.fromDb(rs.getObject("old_measured_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("new_measured_at", LocalDateTime.class)),
            rs.getBigDecimal("old_raw_reading"),
            rs.getBigDecimal("new_raw_reading"),
            rs.getBigDecimal("old_lower_limit"),
            rs.getBigDecimal("new_lower_limit"),
            rs.getBigDecimal("old_upper_limit"),
            rs.getBigDecimal("new_upper_limit"),
            rs.getLong("old_certificate_id"),
            rs.getLong("new_certificate_id"),
            rs.getBigDecimal("old_computed_value"),
            rs.getBigDecimal("new_computed_value"),
            rs.getBoolean("old_passed"),
            rs.getBoolean("new_passed"),
            rs.getString("revised_by"),
            JdbcTimes.fromDb(rs.getObject("revised_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public MeasurementRevisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条修订历史。
     */
    public long insert(MeasurementRevision r) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement_revision "
                            + "(measurement_id, from_version, to_version, old_measured_at, new_measured_at, "
                            + "old_raw_reading, new_raw_reading, old_lower_limit, new_lower_limit, "
                            + "old_upper_limit, new_upper_limit, old_certificate_id, new_certificate_id, "
                            + "old_computed_value, new_computed_value, old_passed, new_passed, "
                            + "revised_by, revised_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, r.measurementId());
            ps.setLong(2, r.fromVersion());
            ps.setLong(3, r.toVersion());
            ps.setObject(4, JdbcTimes.toDb(r.oldMeasuredAt()));
            ps.setObject(5, JdbcTimes.toDb(r.newMeasuredAt()));
            ps.setBigDecimal(6, r.oldRawReading());
            ps.setBigDecimal(7, r.newRawReading());
            ps.setBigDecimal(8, r.oldLowerLimit());
            ps.setBigDecimal(9, r.newLowerLimit());
            ps.setBigDecimal(10, r.oldUpperLimit());
            ps.setBigDecimal(11, r.newUpperLimit());
            ps.setLong(12, r.oldCertificateId());
            ps.setLong(13, r.newCertificateId());
            ps.setBigDecimal(14, r.oldComputedValue());
            ps.setBigDecimal(15, r.newComputedValue());
            ps.setBoolean(16, r.oldPassed());
            ps.setBoolean(17, r.newPassed());
            ps.setString(18, r.revisedBy());
            ps.setObject(19, JdbcTimes.toDb(r.revisedAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询某测量的全部修订历史，按修订先后（自增主键）升序。
     */
    public List<MeasurementRevision> findByMeasurementId(long measurementId) {
        return jdbc.query(
                "SELECT * FROM measurement_revision WHERE measurement_id = ? ORDER BY id",
                MAPPER, measurementId);
    }
}
