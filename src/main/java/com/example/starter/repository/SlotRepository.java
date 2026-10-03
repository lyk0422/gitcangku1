package com.example.starter.repository;

import com.example.starter.domain.DeicingSlot;
import com.example.starter.domain.SlotStatus;
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

/** 除冰时隙数据访问；状态流转均用条件更新保证并发下只有一个结果。 */
@Repository
public class SlotRepository {

    private static final RowMapper<DeicingSlot> MAPPER = (rs, n) -> new DeicingSlot(
            rs.getLong("id"), rs.getLong("vehicle_id"), rs.getLong("flight_id"),
            UtcTimes.toInstant(rs, "slot_start"), UtcTimes.toInstant(rs, "slot_end"),
            SlotStatus.valueOf(rs.getString("status")),
            rs.getString("request_id"), rs.getString("request_hash"),
            UtcTimes.toInstant(rs, "created_at"));

    private final JdbcTemplate jdbc;

    public SlotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DeicingSlot> findById(long id) {
        return jdbc.query("SELECT * FROM deicing_slot WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<DeicingSlot> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM deicing_slot WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /** 查询车辆未取消时隙中与[start, end)重叠的记录（左闭右开，端点相接不算重叠）。 */
    public List<DeicingSlot> findOverlapping(long vehicleId, Instant start, Instant end) {
        return jdbc.query("SELECT * FROM deicing_slot WHERE vehicle_id = ? AND status <> 'CANCELLED'"
                + " AND slot_start < ? AND slot_end > ? ORDER BY slot_start",
                MAPPER, vehicleId, UtcTimes.toTimestamp(end), UtcTimes.toTimestamp(start));
    }

    public List<DeicingSlot> findByVehicleId(long vehicleId) {
        return jdbc.query("SELECT * FROM deicing_slot WHERE vehicle_id = ? ORDER BY slot_start, id",
                MAPPER, vehicleId);
    }

    public long insert(long vehicleId, long flightId, Instant start, Instant end, String requestId,
            String requestHash, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        Timestamp startTs = UtcTimes.toTimestamp(start);
        Timestamp endTs = UtcTimes.toTimestamp(end);
        Timestamp createdTs = UtcTimes.toTimestamp(createdAt);
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO deicing_slot (vehicle_id, flight_id, slot_start, slot_end, status,"
                    + " request_id, request_hash, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, vehicleId);
            ps.setLong(2, flightId);
            ps.setTimestamp(3, startTs);
            ps.setTimestamp(4, endTs);
            ps.setString(5, SlotStatus.BOOKED.name());
            ps.setString(6, requestId);
            ps.setString(7, requestHash);
            ps.setTimestamp(8, createdTs);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 仅BOOKED可流转为COMPLETED，与取消竞争时只有一个成功。 */
    public boolean markCompleted(long id) {
        return jdbc.update("UPDATE deicing_slot SET status = 'COMPLETED' WHERE id = ? AND status = 'BOOKED'",
                id) == 1;
    }

    /** 仅未开始的BOOKED时隙可取消；已完成或已开始的作业不能通过取消撤掉。 */
    public boolean markCancelled(long id, Instant now) {
        return jdbc.update("UPDATE deicing_slot SET status = 'CANCELLED'"
                + " WHERE id = ? AND status = 'BOOKED' AND slot_start > ?",
                id, UtcTimes.toTimestamp(now)) == 1;
    }
}