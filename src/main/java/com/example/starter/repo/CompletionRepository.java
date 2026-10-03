package com.example.starter.repo;

import com.example.starter.model.DeicingCompletion;
import com.example.starter.model.FluidType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 除冰完成记录数据访问。
 */
@Repository
public class CompletionRepository {

    private static final RowMapper<DeicingCompletion> MAPPER = CompletionRepository::mapRow;

    private final JdbcTemplate jdbc;

    public CompletionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DeicingCompletion insert(DeicingCompletion completion) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            var ps = conn.prepareStatement(
                    "INSERT INTO deicing_completion (slot_id, fluid_type, completed_at, deadline_at)"
                            + " VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, completion.slotId());
            ps.setString(2, completion.fluidType().name());
            ps.setTimestamp(3, Timestamp.from(completion.completedAt()));
            ps.setTimestamp(4, Timestamp.from(completion.deadlineAt()));
            return ps;
        }, keys);
        return new DeicingCompletion(keys.getKey().longValue(), completion.slotId(),
                completion.fluidType(), completion.completedAt(), completion.deadlineAt());
    }

    public Optional<DeicingCompletion> findBySlotId(long slotId) {
        return jdbc.query("SELECT * FROM deicing_completion WHERE slot_id = ?", MAPPER, slotId)
                .stream().findFirst();
    }

    /**
     * 查询航班最近一次完成记录。
     */
    public Optional<DeicingCompletion> findLatestByFlightId(long flightId) {
        return jdbc.query("SELECT c.* FROM deicing_completion c"
                        + " JOIN deicing_slot s ON s.id = c.slot_id"
                        + " WHERE s.flight_id = ? ORDER BY c.completed_at DESC, c.id DESC LIMIT 1",
                MAPPER, flightId).stream().findFirst();
    }

    private static DeicingCompletion mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DeicingCompletion(
                rs.getLong("id"),
                rs.getLong("slot_id"),
                FluidType.valueOf(rs.getString("fluid_type")),
                rs.getTimestamp("completed_at").toInstant(),
                rs.getTimestamp("deadline_at").toInstant());
    }
}