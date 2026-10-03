package com.example.starter.repo;

import com.example.starter.model.AssignmentRow;
import com.example.starter.model.AssignmentStatus;
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
 * 指派表访问。占用区间规则：未登轮占用预计区间；已登轮未离轮视为无限延续；
 * 已离轮占用到实际离轮时间；已释放或航段已取消不再占用。
 */
@Repository
public class AssignmentRepository {

    private static final RowMapper<AssignmentRow> MAPPER = (rs, rowNum) -> new AssignmentRow(
            rs.getLong("id"), rs.getLong("segment_id"), rs.getLong("pilot_id"),
            rs.getString("request_id"), rs.getString("request_fingerprint"),
            AssignmentStatus.valueOf(rs.getString("status")),
            JdbcTimes.toInstant(rs, "boarded_at_utc"), JdbcTimes.toInstant(rs, "disembarked_at_utc"),
            rs.getString("board_request_id"), rs.getString("board_request_fingerprint"),
            JdbcTimes.toInstant(rs, "created_at_utc"), JdbcTimes.toInstant(rs, "released_at_utc"),
            rs.getString("release_reason"));

    private static final String OVERLAP_SQL =
            "SELECT COUNT(*) FROM assignment a JOIN segment s ON s.id = a.segment_id"
                    + " WHERE a.pilot_id = ? AND a.status IN ('ACTIVE', 'COMPLETED') AND s.status <> 'CANCELLED'"
                    + " AND s.est_start_utc < ?"
                    + " AND (CASE WHEN a.boarded_at_utc IS NULL THEN s.est_end_utc"
                    + "           WHEN a.disembarked_at_utc IS NULL THEN TIMESTAMP '9999-12-31 23:59:59'"
                    + "           ELSE a.disembarked_at_utc END) > ?";

    private final JdbcTemplate jdbc;

    public AssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long segmentId, long pilotId, String requestId, String fingerprint,
                       Instant createdAtUtc) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO assignment (segment_id, pilot_id, request_id, request_fingerprint,"
                            + " status, created_at_utc) VALUES (?, ?, ?, ?, 'ACTIVE', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, segmentId);
            ps.setLong(2, pilotId);
            ps.setString(3, requestId);
            ps.setString(4, fingerprint);
            ps.setObject(5, JdbcTimes.toColumn(createdAtUtc));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<AssignmentRow> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM assignment WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    public Optional<AssignmentRow> findActiveBySegmentId(long segmentId) {
        return jdbc.query("SELECT * FROM assignment WHERE segment_id = ? AND status = 'ACTIVE'"
                        + " ORDER BY id DESC", MAPPER, segmentId).stream().findFirst();
    }

    public Optional<AssignmentRow> findLatestBySegmentId(long segmentId) {
        return jdbc.query("SELECT * FROM assignment WHERE segment_id = ? ORDER BY id DESC LIMIT 1",
                MAPPER, segmentId).stream().findFirst();
    }

    public List<AssignmentRow> findByPilotId(long pilotId) {
        return jdbc.query("SELECT * FROM assignment WHERE pilot_id = ? ORDER BY id", MAPPER, pilotId);
    }

    /**
     * 统计与新区间 [start, end) 重叠的生效占用数，半开区间，端点相接不算重叠。
     */
    public int countOverlapping(long pilotId, Instant startUtc, Instant endUtc) {
        Integer count = jdbc.queryForObject(OVERLAP_SQL, Integer.class, pilotId,
                JdbcTimes.toColumn(endUtc), JdbcTimes.toColumn(startUtc));
        return count == null ? 0 : count;
    }

    /**
     * 守卫式登轮：仅当指派生效且未登轮时更新，返回受影响行数（0 表示竞争中失败）。
     */
    public int markBoarded(long id, Instant boardedAtUtc, String boardRequestId,
                           String boardFingerprint) {
        return jdbc.update("UPDATE assignment SET boarded_at_utc = ?, board_request_id = ?,"
                        + " board_request_fingerprint = ?"
                        + " WHERE id = ? AND status = 'ACTIVE' AND boarded_at_utc IS NULL",
                JdbcTimes.toColumn(boardedAtUtc), boardRequestId, boardFingerprint, id);
    }

    /**
     * 守卫式释放：仅当指派生效且未登轮时更新，与登轮竞争时只有一方成功。
     */
    public int releaseIfNotBoarded(long id, Instant releasedAtUtc, String reason) {
        return jdbc.update("UPDATE assignment SET status = 'RELEASED', released_at_utc = ?,"
                        + " release_reason = ?"
                        + " WHERE id = ? AND status = 'ACTIVE' AND boarded_at_utc IS NULL",
                JdbcTimes.toColumn(releasedAtUtc), reason, id);
    }

    public void complete(long id, Instant disembarkedAtUtc) {
        jdbc.update("UPDATE assignment SET status = 'COMPLETED', disembarked_at_utc = ?"
                        + " WHERE id = ?", JdbcTimes.toColumn(disembarkedAtUtc), id);
    }

    /**
     * 船舶取消航段时释放生效指派，已发生的登轮/离轮时间保留不变。
     */
    public int releaseActiveBySegmentId(long segmentId, Instant releasedAtUtc, String reason) {
        return jdbc.update("UPDATE assignment SET status = 'RELEASED', released_at_utc = ?,"
                        + " release_reason = ? WHERE segment_id = ? AND status = 'ACTIVE'",
                JdbcTimes.toColumn(releasedAtUtc), reason, segmentId);
    }
}