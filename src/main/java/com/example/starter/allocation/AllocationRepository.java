package com.example.starter.allocation;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 接收时隙分配的数据访问。
 */
@Repository
public class AllocationRepository {

    private static final RowMapper<SlotAllocation> MAPPER = (rs, rowNum) -> {
        long actual = rs.getLong("actual_bytes");
        Long actualBytes = rs.wasNull() ? null : actual;
        return new SlotAllocation(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("request_fingerprint"),
                rs.getLong("prediction_id"),
                rs.getString("prediction_key"),
                rs.getInt("prediction_version"),
                rs.getLong("station_id"),
                rs.getTimestamp("slot_start_utc").toInstant(),
                rs.getTimestamp("slot_end_utc").toInstant(),
                rs.getInt("capacity_snapshot_mb_per_min"),
                rs.getLong("planned_data_mb"),
                AllocationStatus.valueOf(rs.getString("status")),
                actualBytes,
                rs.getString("completion_request_id"),
                rs.getString("cancel_reason"),
                rs.getTimestamp("created_at_utc").toInstant(),
                rs.getTimestamp("updated_at_utc").toInstant());
    };

    private final JdbcTemplate jdbc;

    public AllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按幂等键查询。
     */
    public Optional<SlotAllocation> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM slot_allocation WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 按主键查询。
     */
    public Optional<SlotAllocation> findById(long id) {
        return jdbc.query("SELECT * FROM slot_allocation WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 插入分配记录并返回主键。
     */
    public long insert(SlotAllocation allocation) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO slot_allocation (request_id, request_fingerprint, prediction_id,"
                            + " prediction_key, prediction_version, station_id, slot_start_utc, slot_end_utc,"
                            + " capacity_snapshot_mb_per_min, planned_data_mb, status, actual_bytes,"
                            + " completion_request_id, cancel_reason, created_at_utc, updated_at_utc)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, allocation.requestId());
            ps.setString(2, allocation.requestFingerprint());
            ps.setLong(3, allocation.predictionId());
            ps.setString(4, allocation.predictionKey());
            ps.setInt(5, allocation.predictionVersion());
            ps.setLong(6, allocation.stationId());
            ps.setTimestamp(7, Timestamp.from(allocation.slotStartUtc()));
            ps.setTimestamp(8, Timestamp.from(allocation.slotEndUtc()));
            ps.setInt(9, allocation.capacitySnapshotMbPerMin());
            ps.setLong(10, allocation.plannedDataMb());
            ps.setString(11, allocation.status().name());
            ps.setNull(12, Types.BIGINT);
            ps.setNull(13, Types.VARCHAR);
            ps.setNull(14, Types.VARCHAR);
            ps.setTimestamp(15, Timestamp.from(allocation.createdAtUtc()));
            ps.setTimestamp(16, Timestamp.from(allocation.updatedAtUtc()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询同站与指定时段重叠且仍占用天线的记录（CONFIRMED 或 COMPLETED）。
     */
    public List<SlotAllocation> findOccupyingOverlap(long stationId, Instant slotStart, Instant slotEnd) {
        return jdbc.query("SELECT * FROM slot_allocation WHERE station_id = ?"
                        + " AND status IN ('CONFIRMED', 'COMPLETED')"
                        + " AND slot_start_utc < ? AND slot_end_utc > ?",
                MAPPER, stationId, Timestamp.from(slotEnd), Timestamp.from(slotStart));
    }

    /**
     * 查询某预测业务键下未开始的已确认分配，用于预测修订后的重新校核。
     */
    public List<SlotAllocation> findConfirmedNotStarted(String predictionKey, Instant now) {
        return jdbc.query("SELECT * FROM slot_allocation WHERE prediction_key = ? AND status = 'CONFIRMED'"
                        + " AND slot_start_utc > ?",
                MAPPER, predictionKey, Timestamp.from(now));
    }

    /**
     * 校核不通过的分配转为待重新分配，返回受影响行数。
     */
    public int markPendingReassignment(long id, Instant now) {
        return jdbc.update("UPDATE slot_allocation SET status = 'PENDING_REASSIGNMENT', updated_at_utc = ?"
                        + " WHERE id = ? AND status = 'CONFIRMED'",
                Timestamp.from(now), id);
    }

    /**
     * 仅当仍处于已确认状态时完成接收，返回受影响行数。
     */
    public int completeIfConfirmed(long id, String completionRequestId, long actualBytes, Instant now) {
        return jdbc.update("UPDATE slot_allocation SET status = 'COMPLETED', actual_bytes = ?,"
                        + " completion_request_id = ?, updated_at_utc = ? WHERE id = ? AND status = 'CONFIRMED'",
                actualBytes, completionRequestId, Timestamp.from(now), id);
    }

    /**
     * 仅当仍处于非终态时取消，返回受影响行数。
     */
    public int cancelIfActive(long id, String reason, Instant now) {
        return jdbc.update("UPDATE slot_allocation SET status = 'CANCELLED', cancel_reason = ?,"
                        + " updated_at_utc = ? WHERE id = ? AND status IN ('CONFIRMED', 'PENDING_REASSIGNMENT')",
                reason, Timestamp.from(now), id);
    }

    /**
     * 站点时隙时间线：与查询区间相交的全部分配，按起点升序。
     */
    public List<SlotAllocation> findTimeline(long stationId, Instant from, Instant to) {
        return jdbc.query("SELECT * FROM slot_allocation WHERE station_id = ?"
                        + " AND slot_start_utc < ? AND slot_end_utc > ? ORDER BY slot_start_utc, id",
                MAPPER, stationId, Timestamp.from(to), Timestamp.from(from));
    }

    /**
     * 待重新分配列表。
     */
    public List<SlotAllocation> findPendingReassignment() {
        return jdbc.query("SELECT * FROM slot_allocation WHERE status = 'PENDING_REASSIGNMENT'"
                + " ORDER BY updated_at_utc, id", MAPPER);
    }

    /**
     * 站点接收实绩（已完成记录）。
     */
    public List<SlotAllocation> findCompletedByStation(long stationId) {
        return jdbc.query("SELECT * FROM slot_allocation WHERE station_id = ? AND status = 'COMPLETED'"
                + " ORDER BY slot_start_utc, id", MAPPER, stationId);
    }
}