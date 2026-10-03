package com.example.starter.repo;

import com.example.starter.domain.Assignment;
import com.example.starter.domain.AssignmentStatus;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 指派仓储。时间列一律按 UTC 存取。
 */
@Repository
public class AssignmentRepository {

    /** 已登轮未离轮时占用的远端边界，视为无限远。 */
    private static final OffsetDateTime FAR_FUTURE = OffsetDateTime.parse("9999-12-31T00:00:00Z");

    private static final RowMapper<Assignment> MAPPER = (rs, rowNum) -> new Assignment(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("segment_id"),
            rs.getLong("pilot_id"),
            AssignmentStatus.valueOf(rs.getString("status")),
            SegmentRepository.toInstant(rs.getObject("est_start_utc", OffsetDateTime.class)),
            SegmentRepository.toInstant(rs.getObject("est_end_utc", OffsetDateTime.class)),
            SegmentRepository.toInstant(rs.getObject("boarded_at_utc", OffsetDateTime.class)),
            SegmentRepository.toInstant(rs.getObject("departed_at_utc", OffsetDateTime.class)),
            SegmentRepository.toInstant(rs.getObject("revoked_at_utc", OffsetDateTime.class)),
            rs.getString("boarding_request_id"),
            SegmentRepository.toInstant(rs.getObject("created_at_utc", OffsetDateTime.class)));

    private final JdbcTemplate jdbc;

    public AssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String requestId, long segmentId, long pilotId, Instant estStartUtc, Instant estEndUtc,
                       Instant createdAtUtc) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO assignment (request_id, segment_id, pilot_id, status, est_start_utc, est_end_utc,"
                            + " created_at_utc) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setLong(2, segmentId);
            ps.setLong(3, pilotId);
            ps.setObject(4, SegmentRepository.toUtc(estStartUtc));
            ps.setObject(5, SegmentRepository.toUtc(estEndUtc));
            ps.setObject(6, SegmentRepository.toUtc(createdAtUtc));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<Assignment> findById(long id) {
        return jdbc.query("SELECT * FROM assignment WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * 按主键加行锁读取，用于串行化登轮与撤销的竞争。
     */
    public Optional<Assignment> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM assignment WHERE id = ? FOR UPDATE", MAPPER, id).stream().findFirst();
    }

    public Optional<Assignment> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM assignment WHERE request_id = ?", MAPPER, requestId).stream().findFirst();
    }

    public List<Assignment> findByPilotId(long pilotId) {
        return jdbc.query("SELECT * FROM assignment WHERE pilot_id = ? ORDER BY est_start_utc, id", MAPPER, pilotId);
    }

    public List<Assignment> findBySegmentId(long segmentId) {
        return jdbc.query("SELECT * FROM assignment WHERE segment_id = ? ORDER BY id", MAPPER, segmentId);
    }

    /**
     * 查询引航员与 [startUtc, endUtc) 重叠的占用指派（含已完成的实际区间，不含已撤销）：未登轮按预计区间占用，
     * 已登轮未离轮视为占用到无限远，已离轮按实际离轮时刻截止。
     */
    public List<Assignment> findOccupiedOverlapping(long pilotId, Instant startUtc, Instant endUtc) {
        return jdbc.query(
                "SELECT * FROM assignment WHERE pilot_id = ? AND status IN ('ACTIVE', 'BOARDED', 'COMPLETED')"
                        + " AND est_start_utc < ?"
                        + " AND COALESCE(departed_at_utc,"
                        + "     CASE WHEN boarded_at_utc IS NOT NULL THEN ? ELSE est_end_utc END) > ?",
                MAPPER, pilotId, SegmentRepository.toUtc(endUtc), FAR_FUTURE, SegmentRepository.toUtc(startUtc));
    }

    public void markBoarded(long id, Instant boardedAtUtc, String boardingRequestId) {
        jdbc.update("UPDATE assignment SET status = 'BOARDED', boarded_at_utc = ?, boarding_request_id = ?"
                        + " WHERE id = ?",
                SegmentRepository.toUtc(boardedAtUtc), boardingRequestId, id);
    }

    public void markCompleted(long id, Instant departedAtUtc) {
        jdbc.update("UPDATE assignment SET status = 'COMPLETED', departed_at_utc = ? WHERE id = ?",
                SegmentRepository.toUtc(departedAtUtc), id);
    }

    public void markRevoked(long id, Instant revokedAtUtc) {
        jdbc.update("UPDATE assignment SET status = 'REVOKED', revoked_at_utc = ? WHERE id = ?",
                SegmentRepository.toUtc(revokedAtUtc), id);
    }
}