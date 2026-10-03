package com.example.starter.repo;

import com.example.starter.domain.Segment;
import com.example.starter.domain.SegmentStatus;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 航段申请仓储。时间列一律按 UTC 存取。
 */
@Repository
public class SegmentRepository {

    private static final RowMapper<Segment> MAPPER = (rs, rowNum) -> new Segment(
            rs.getLong("id"),
            rs.getString("vessel_code"),
            rs.getInt("required_grade"),
            toInstant(rs.getObject("est_start_utc", OffsetDateTime.class)),
            toInstant(rs.getObject("est_end_utc", OffsetDateTime.class)),
            rs.getString("boarding_point"),
            rs.getString("original_boarding_point"),
            SegmentStatus.valueOf(rs.getString("status")),
            toInstant(rs.getObject("cancelled_at_utc", OffsetDateTime.class)),
            toInstant(rs.getObject("created_at_utc", OffsetDateTime.class)));

    private final JdbcTemplate jdbc;

    public SegmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static Instant toInstant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    static OffsetDateTime toUtc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public long insert(String vesselCode, int requiredGrade, Instant estStartUtc, Instant estEndUtc,
                       String boardingPoint, Instant createdAtUtc) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO segment (vessel_code, required_grade, est_start_utc, est_end_utc,"
                            + " boarding_point, original_boarding_point, status, created_at_utc)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'REQUESTED', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, vesselCode);
            ps.setInt(2, requiredGrade);
            ps.setObject(3, toUtc(estStartUtc));
            ps.setObject(4, toUtc(estEndUtc));
            ps.setString(5, boardingPoint);
            ps.setString(6, boardingPoint);
            ps.setObject(7, toUtc(createdAtUtc));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<Segment> findById(long id) {
        return jdbc.query("SELECT * FROM segment WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<Segment> findByVesselCode(String vesselCode) {
        return jdbc.query("SELECT * FROM segment WHERE vessel_code = ? ORDER BY est_start_utc, id", MAPPER, vesselCode);
    }

    public void updateStatus(long id, SegmentStatus status, Instant cancelledAtUtc) {
        jdbc.update("UPDATE segment SET status = ?, cancelled_at_utc = ? WHERE id = ?",
                status.name(), toUtc(cancelledAtUtc), id);
    }

    /**
     * 修改当前登轮点，original_boarding_point 保持不变。
     */
    public void updateBoardingPoint(long id, String boardingPoint) {
        jdbc.update("UPDATE segment SET boarding_point = ? WHERE id = ?", boardingPoint, id);
    }
}