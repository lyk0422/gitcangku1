package com.example.starter.repo;

import com.example.starter.model.FluidType;
import com.example.starter.model.TakeoffClearance;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 起飞放行记录数据访问。
 */
@Repository
public class ClearanceRepository {

    private static final RowMapper<TakeoffClearance> MAPPER = ClearanceRepository::mapRow;

    private final JdbcTemplate jdbc;

    public ClearanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TakeoffClearance insert(TakeoffClearance clearance) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            var ps = conn.prepareStatement(
                    "INSERT INTO takeoff_clearance (request_id, flight_id, completion_id, confirmed_at,"
                            + " fluid_type, completed_at, deadline_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, clearance.requestId());
            ps.setLong(2, clearance.flightId());
            ps.setLong(3, clearance.completionId());
            ps.setTimestamp(4, Timestamp.from(clearance.confirmedAt()));
            ps.setString(5, clearance.fluidType().name());
            ps.setTimestamp(6, Timestamp.from(clearance.completedAt()));
            ps.setTimestamp(7, Timestamp.from(clearance.deadlineAt()));
            return ps;
        }, keys);
        return findByRequestId(clearance.requestId()).orElseThrow();
    }

    public Optional<TakeoffClearance> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM takeoff_clearance WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    public List<TakeoffClearance> findByFlightId(long flightId) {
        return jdbc.query("SELECT * FROM takeoff_clearance WHERE flight_id = ? ORDER BY id",
                MAPPER, flightId);
    }

    private static TakeoffClearance mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TakeoffClearance(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getLong("flight_id"),
                rs.getLong("completion_id"),
                rs.getTimestamp("confirmed_at").toInstant(),
                FluidType.valueOf(rs.getString("fluid_type")),
                rs.getTimestamp("completed_at").toInstant(),
                rs.getTimestamp("deadline_at").toInstant());
    }
}