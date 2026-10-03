package com.example.starter.repo;

import com.example.starter.model.SegmentRow;
import com.example.starter.model.SegmentStatus;
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
 * 航段表访问。
 */
@Repository
public class SegmentRepository {

    private static final RowMapper<SegmentRow> MAPPER = (rs, rowNum) -> new SegmentRow(
            rs.getLong("id"), rs.getString("vessel_code"), rs.getInt("required_level"),
            JdbcTimes.toInstant(rs, "est_start_utc"), JdbcTimes.toInstant(rs, "est_end_utc"),
            rs.getString("boarding_point"), SegmentStatus.valueOf(rs.getString("status")),
            JdbcTimes.toInstant(rs, "created_at_utc"), JdbcTimes.toInstant(rs, "cancelled_at_utc"));

    private final JdbcTemplate jdbc;

    public SegmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String vesselCode, int requiredLevel, Instant estStartUtc, Instant estEndUtc,
                       String boardingPoint, Instant createdAtUtc) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO segment (vessel_code, required_level, est_start_utc, est_end_utc,"
                            + " boarding_point, status, created_at_utc)"
                            + " VALUES (?, ?, ?, ?, ?, 'REQUESTED', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, vesselCode);
            ps.setInt(2, requiredLevel);
            ps.setObject(3, JdbcTimes.toColumn(estStartUtc));
            ps.setObject(4, JdbcTimes.toColumn(estEndUtc));
            ps.setString(5, boardingPoint);
            ps.setObject(6, JdbcTimes.toColumn(createdAtUtc));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<SegmentRow> findById(long id) {
        return jdbc.query("SELECT * FROM segment WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public List<SegmentRow> findByVesselCode(String vesselCode) {
        return jdbc.query("SELECT * FROM segment WHERE vessel_code = ? ORDER BY est_start_utc, id",
                MAPPER, vesselCode);
    }

    public void updateStatus(long id, SegmentStatus status) {
        jdbc.update("UPDATE segment SET status = ? WHERE id = ?", status.name(), id);
    }

    public void markCancelled(long id, Instant cancelledAtUtc) {
        jdbc.update("UPDATE segment SET status = 'CANCELLED', cancelled_at_utc = ? WHERE id = ?",
                JdbcTimes.toColumn(cancelledAtUtc), id);
    }

    public void updateBoardingPoint(long id, String boardingPoint) {
        jdbc.update("UPDATE segment SET boarding_point = ? WHERE id = ?", boardingPoint, id);
    }
}