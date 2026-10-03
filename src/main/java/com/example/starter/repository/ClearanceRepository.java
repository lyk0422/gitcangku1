package com.example.starter.repository;

import com.example.starter.domain.ClearanceStatus;
import com.example.starter.domain.TakeoffClearance;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 起飞确认记录数据访问，放行与拒绝历史均落库留痕。 */
@Repository
public class ClearanceRepository {

    private static final RowMapper<TakeoffClearance> MAPPER = (rs, n) -> new TakeoffClearance(
            rs.getLong("id"), rs.getLong("flight_id"),
            rs.getObject("completion_id") == null ? null : rs.getLong("completion_id"),
            rs.getString("request_id"), rs.getString("request_hash"),
            ClearanceStatus.valueOf(rs.getString("status")), rs.getString("reason"),
            UtcTimes.toInstant(rs, "confirmed_at"));

    private final JdbcTemplate jdbc;

    public ClearanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TakeoffClearance> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM takeoff_clearance WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /** 同一完成记录是否已放行，用于阻止第二次放行。 */
    public boolean existsClearedByCompletionId(long completionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM takeoff_clearance"
                + " WHERE completion_id = ? AND status = 'CLEARED'", Integer.class, completionId);
        return count != null && count > 0;
    }

    public List<TakeoffClearance> findByFlightId(long flightId) {
        return jdbc.query("SELECT * FROM takeoff_clearance WHERE flight_id = ?"
                + " ORDER BY confirmed_at, id", MAPPER, flightId);
    }

    public long insert(long flightId, Long completionId, String requestId, String requestHash,
            ClearanceStatus status, String reason, Instant confirmedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        Timestamp confirmedTs = UtcTimes.toTimestamp(confirmedAt);
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO takeoff_clearance (flight_id, completion_id, request_id, request_hash,"
                    + " status, reason, confirmed_at) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, flightId);
            if (completionId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, completionId);
            }
            ps.setString(3, requestId);
            ps.setString(4, requestHash);
            ps.setString(5, status.name());
            ps.setString(6, reason);
            ps.setTimestamp(7, confirmedTs);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }
}