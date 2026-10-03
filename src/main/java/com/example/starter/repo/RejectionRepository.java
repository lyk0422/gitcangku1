package com.example.starter.repo;

import com.example.starter.model.TakeoffRejection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 起飞拒绝历史数据访问，只增不改。
 */
@Repository
public class RejectionRepository {

    private static final RowMapper<TakeoffRejection> MAPPER = RejectionRepository::mapRow;

    private final JdbcTemplate jdbc;

    public RejectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TakeoffRejection insert(TakeoffRejection rejection) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            var ps = conn.prepareStatement(
                    "INSERT INTO takeoff_rejection (flight_id, request_id, reason, attempted_at)"
                            + " VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, rejection.flightId());
            ps.setString(2, rejection.requestId());
            ps.setString(3, rejection.reason());
            ps.setTimestamp(4, Timestamp.from(rejection.attemptedAt()));
            return ps;
        }, keys);
        return new TakeoffRejection(keys.getKey().longValue(), rejection.flightId(),
                rejection.requestId(), rejection.reason(), rejection.attemptedAt());
    }

    public List<TakeoffRejection> findByFlightId(long flightId) {
        return jdbc.query("SELECT * FROM takeoff_rejection WHERE flight_id = ? ORDER BY id",
                MAPPER, flightId);
    }

    private static TakeoffRejection mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TakeoffRejection(
                rs.getLong("id"),
                rs.getLong("flight_id"),
                rs.getString("request_id"),
                rs.getString("reason"),
                rs.getTimestamp("attempted_at").toInstant());
    }
}