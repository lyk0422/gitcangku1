package com.example.starter.repo;

import com.example.starter.model.DeicingSlot;
import com.example.starter.model.SlotStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
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

/**
 * 除冰作业时隙数据访问。
 */
@Repository
public class SlotRepository {

    private static final RowMapper<DeicingSlot> MAPPER = SlotRepository::mapRow;

    private final JdbcTemplate jdbc;

    public SlotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DeicingSlot> findById(long id) {
        return jdbc.query("SELECT * FROM deicing_slot WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<DeicingSlot> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM deicing_slot WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    public DeicingSlot insert(DeicingSlot slot) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            var ps = conn.prepareStatement(
                    "INSERT INTO deicing_slot (request_id, vehicle_id, flight_id, start_utc, end_utc, status)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, slot.requestId());
            ps.setLong(2, slot.vehicleId());
            ps.setLong(3, slot.flightId());
            ps.setTimestamp(4, Timestamp.from(slot.startUtc()));
            ps.setTimestamp(5, Timestamp.from(slot.endUtc()));
            ps.setString(6, slot.status().name());
            return ps;
        }, keys);
        return findById(keys.getKey().longValue()).orElseThrow();
    }

    /**
     * 条件更新状态，仅当前状态匹配时生效，返回受影响行数（0 或 1）。
     */
    public int transitionStatus(long id, SlotStatus expected, SlotStatus target) {
        return jdbc.update("UPDATE deicing_slot SET status = ? WHERE id = ? AND status = ?",
                target.name(), id, expected.name());
    }

    /**
     * 查询车辆未取消且与指定区间（左闭右开）重叠的时隙。
     */
    public List<DeicingSlot> findOverlapping(long vehicleId, Instant startUtc, Instant endUtc) {
        return jdbc.query("SELECT * FROM deicing_slot WHERE vehicle_id = ? AND status <> 'CANCELLED'"
                        + " AND start_utc < ? AND end_utc > ?",
                MAPPER, vehicleId, Timestamp.from(endUtc), Timestamp.from(startUtc));
    }

    public List<DeicingSlot> findByVehicleId(long vehicleId) {
        return jdbc.query("SELECT * FROM deicing_slot WHERE vehicle_id = ? ORDER BY start_utc, id",
                MAPPER, vehicleId);
    }

    public List<DeicingSlot> findByFlightId(long flightId) {
        return jdbc.query("SELECT * FROM deicing_slot WHERE flight_id = ? ORDER BY start_utc, id",
                MAPPER, flightId);
    }

    private static DeicingSlot mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DeicingSlot(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getLong("vehicle_id"),
                rs.getLong("flight_id"),
                rs.getTimestamp("start_utc").toInstant(),
                rs.getTimestamp("end_utc").toInstant(),
                SlotStatus.valueOf(rs.getString("status")));
    }
}