package com.example.starter.repository;

import com.example.starter.domain.DeicingCompletion;
import com.example.starter.domain.FluidType;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 除冰完成记录数据访问。 */
@Repository
public class CompletionRepository {

    private static final RowMapper<DeicingCompletion> MAPPER = (rs, n) -> new DeicingCompletion(
            rs.getLong("id"), rs.getLong("slot_id"), FluidType.valueOf(rs.getString("fluid_type")),
            UtcTimes.toInstant(rs, "completed_at"), UtcTimes.toInstant(rs, "deadline_at"));

    private final JdbcTemplate jdbc;

    public CompletionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DeicingCompletion> findBySlotId(long slotId) {
        return jdbc.query("SELECT * FROM deicing_completion WHERE slot_id = ?", MAPPER, slotId)
                .stream().findFirst();
    }

    /** 行锁读取航班最近一次完成记录，用于起飞确认的并发互斥。 */
    public Optional<DeicingCompletion> findLatestByFlightIdForUpdate(long flightId) {
        return jdbc.query("SELECT c.* FROM deicing_completion c"
                + " JOIN deicing_slot s ON s.id = c.slot_id"
                + " WHERE s.flight_id = ? ORDER BY c.completed_at DESC, c.id DESC LIMIT 1 FOR UPDATE",
                MAPPER, flightId).stream().findFirst();
    }

    public long insert(long slotId, FluidType fluidType, Instant completedAt, Instant deadlineAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        Timestamp completedTs = UtcTimes.toTimestamp(completedAt);
        Timestamp deadlineTs = UtcTimes.toTimestamp(deadlineAt);
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO deicing_completion (slot_id, fluid_type, completed_at, deadline_at)"
                    + " VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, slotId);
            ps.setString(2, fluidType.name());
            ps.setTimestamp(3, completedTs);
            ps.setTimestamp(4, deadlineTs);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }
}