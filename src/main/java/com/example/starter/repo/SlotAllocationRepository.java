package com.example.starter.repo;

import com.example.starter.model.AllocationStatus;
import com.example.starter.model.SlotAllocation;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 接收时隙分配数据访问。状态流转通过带状态条件的 UPDATE 保证原子性。
 */
@Repository
public class SlotAllocationRepository {

    private static final RowMapper<SlotAllocation> MAPPER = (rs, n) -> new SlotAllocation(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("request_hash"),
            rs.getLong("prediction_id"),
            rs.getInt("prediction_version"),
            rs.getLong("station_id"),
            rs.getObject("slot_start_utc", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            rs.getObject("slot_end_utc", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            rs.getLong("capacity_bytes_per_min"),
            rs.getLong("planned_bytes"),
            AllocationStatus.valueOf(rs.getString("status")),
            rs.getString("cancel_reason"),
            rs.getObject("actual_bytes") == null ? null : rs.getLong("actual_bytes"),
            rs.getString("completion_request_id"),
            rs.getString("completion_request_hash"),
            rs.getObject("created_at_utc", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            rs.getObject("updated_at_utc", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    private final JdbcTemplate jdbc;

    public SlotAllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static LocalDateTime toUtc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 插入确认后的分配记录并返回主键。 */
    public long insert(SlotAllocation a) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO slot_allocation (request_id, request_hash, prediction_id,"
                            + " prediction_version, station_id, slot_start_utc, slot_end_utc,"
                            + " capacity_bytes_per_min, planned_bytes, status, created_at_utc, updated_at_utc)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, a.requestId());
            ps.setString(2, a.requestHash());
            ps.setLong(3, a.predictionId());
            ps.setInt(4, a.predictionVersion());
            ps.setLong(5, a.stationId());
            ps.setTimestamp(6, Timestamp.valueOf(toUtc(a.slotStartUtc())));
            ps.setTimestamp(7, Timestamp.valueOf(toUtc(a.slotEndUtc())));
            ps.setLong(8, a.capacityBytesPerMin());
            ps.setLong(9, a.plannedBytes());
            ps.setString(10, a.status().name());
            ps.setTimestamp(11, Timestamp.valueOf(toUtc(a.createdAtUtc())));
            ps.setTimestamp(12, Timestamp.valueOf(toUtc(a.updatedAtUtc())));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 按主键查询。 */
    public Optional<SlotAllocation> findById(long id) {
        List<SlotAllocation> rows = jdbc.query(
                "SELECT * FROM slot_allocation WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 按分配幂等请求号查询。 */
    public Optional<SlotAllocation> findByRequestId(String requestId) {
        List<SlotAllocation> rows = jdbc.query(
                "SELECT * FROM slot_allocation WHERE request_id = ?", MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /** 按完成回报幂等请求号查询。 */
    public Optional<SlotAllocation> findByCompletionRequestId(String completionRequestId) {
        List<SlotAllocation> rows = jdbc.query(
                "SELECT * FROM slot_allocation WHERE completion_request_id = ?",
                MAPPER, completionRequestId);
        return rows.stream().findFirst();
    }

    /** 统计同站点已确认且与指定时段重叠的时隙数。 */
    public int countOverlap(long stationId, Instant start, Instant end) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM slot_allocation WHERE station_id = ? AND status = 'CONFIRMED'"
                        + " AND slot_start_utc < ? AND slot_end_utc > ?",
                Integer.class, stationId, Timestamp.valueOf(toUtc(end)),
                Timestamp.valueOf(toUtc(start)));
        return count == null ? 0 : count;
    }

    /** 查询某卫星预测下、尚未开始（时隙开始晚于给定时刻）的已确认分配。 */
    public List<SlotAllocation> findConfirmedNotStarted(String satelliteCode, Instant now) {
        return jdbc.query(
                "SELECT a.* FROM slot_allocation a JOIN pass_prediction p ON a.prediction_id = p.id"
                        + " WHERE p.satellite_code = ? AND a.status = 'CONFIRMED'"
                        + " AND a.slot_start_utc > ?",
                MAPPER, satelliteCode, Timestamp.valueOf(toUtc(now)));
    }

    /** 将分配置为待重新分配。 */
    public int markPendingReassign(long id, Instant now) {
        return jdbc.update(
                "UPDATE slot_allocation SET status = 'PENDING_REASSIGN', updated_at_utc = ?"
                        + " WHERE id = ? AND status = 'CONFIRMED'",
                Timestamp.valueOf(toUtc(now)), id);
    }

    /**
     * 原子取消：仅当处于 CONFIRMED 或 PENDING_REASSIGN 时生效，返回受影响行数。
     * 与完成回报并发时，二者只有一个能改写状态。
     */
    public int cancelIfActive(long id, String reason, Instant now) {
        return jdbc.update(
                "UPDATE slot_allocation SET status = 'CANCELLED', cancel_reason = ?, updated_at_utc = ?"
                        + " WHERE id = ? AND status IN ('CONFIRMED', 'PENDING_REASSIGN')",
                reason, Timestamp.valueOf(toUtc(now)), id);
    }

    /**
     * 原子完成：仅当处于 CONFIRMED 时写入实际字节数与完成幂等信息，返回受影响行数。
     */
    public int completeIfConfirmed(long id, long actualBytes, String completionRequestId,
            String completionRequestHash, Instant now) {
        return jdbc.update(
                "UPDATE slot_allocation SET status = 'COMPLETED', actual_bytes = ?,"
                        + " completion_request_id = ?, completion_request_hash = ?, updated_at_utc = ?"
                        + " WHERE id = ? AND status = 'CONFIRMED'",
                actualBytes, completionRequestId, completionRequestHash,
                Timestamp.valueOf(toUtc(now)), id);
    }

    /** 查询站点在指定范围内的时隙时间线，按开始时间升序。 */
    public List<SlotAllocation> findTimeline(long stationId, Instant from, Instant to) {
        return jdbc.query(
                "SELECT * FROM slot_allocation WHERE station_id = ?"
                        + " AND slot_start_utc < ? AND slot_end_utc > ?"
                        + " ORDER BY slot_start_utc",
                MAPPER, stationId, Timestamp.valueOf(toUtc(to)), Timestamp.valueOf(toUtc(from)));
    }

    /** 按状态查询分配，按主键升序。 */
    public List<SlotAllocation> findByStatus(AllocationStatus status) {
        return jdbc.query(
                "SELECT * FROM slot_allocation WHERE status = ? ORDER BY id",
                MAPPER, status.name());
    }
}