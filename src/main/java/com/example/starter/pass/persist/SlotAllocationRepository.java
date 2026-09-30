package com.example.starter.pass.persist;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 接收时隙分配（接收计划）数据访问。
 *
 * <p>窗口为半开区间 {@code [start, end)}，重叠判定 {@code existing.start < new.end
 * 且 new.start < existing.end}（相接不算重叠）。</p>
 */
@Repository
public class SlotAllocationRepository {

    private static final RowMapper<SlotAllocationPo> MAPPER = (rs, n) -> {
        SlotAllocationPo po = new SlotAllocationPo();
        po.setId(rs.getLong("id"));
        po.setRequestId(rs.getString("request_id"));
        po.setSatelliteCode(rs.getString("satellite_code"));
        po.setStationId(rs.getLong("station_id"));
        po.setStationCode(rs.getString("station_code"));
        po.setAntennaCode(rs.getString("antenna_code"));
        long pid = rs.getLong("prediction_id");
        po.setPredictionId(rs.wasNull() ? null : pid);
        po.setPredictionRevisionNo(rs.getInt("prediction_revision_no"));
        po.setWindowStart(rs.getTimestamp("window_start").toInstant());
        po.setWindowEnd(rs.getTimestamp("window_end").toInstant());
        po.setPassMinElevationDeg(rs.getBigDecimal("pass_min_elevation_deg"));
        po.setPlannedBytes(rs.getLong("planned_bytes"));
        po.setStationMinElevationDeg(rs.getBigDecimal("station_min_elevation_deg"));
        po.setStationBytesPerMinute(rs.getLong("station_bytes_per_minute"));
        po.setStatus(rs.getString("status"));
        po.setPendingReason(rs.getString("pending_reason"));
        long actual = rs.getLong("actual_bytes");
        po.setActualBytes(rs.wasNull() ? null : actual);
        po.setCancelReason(rs.getString("cancel_reason"));
        po.setConfirmedAt(toInstant(rs, "confirmed_at"));
        po.setReceivedAt(toInstant(rs, "received_at"));
        po.setCancelledAt(toInstant(rs, "cancelled_at"));
        po.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        po.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return po;
    };

    private static Instant toInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private final JdbcTemplate jdbcTemplate;

    public SlotAllocationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 插入时隙行并返回生成主键。 */
    public long insert(SlotAllocationPo po) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO slot_allocation(request_id, satellite_code, station_id, station_code, "
                            + "antenna_code, prediction_id, prediction_revision_no, window_start, window_end, "
                            + "pass_min_elevation_deg, planned_bytes, station_min_elevation_deg, "
                            + "station_bytes_per_minute, status, pending_reason, actual_bytes, cancel_reason, "
                            + "confirmed_at, received_at, cancelled_at, created_at, updated_at) "
                            + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            int i = 1;
            ps.setString(i++, po.getRequestId());
            ps.setString(i++, po.getSatelliteCode());
            ps.setLong(i++, po.getStationId());
            ps.setString(i++, po.getStationCode());
            ps.setString(i++, po.getAntennaCode());
            if (po.getPredictionId() == null) {
                ps.setNull(i++, java.sql.Types.BIGINT);
            } else {
                ps.setLong(i++, po.getPredictionId());
            }
            ps.setInt(i++, po.getPredictionRevisionNo());
            ps.setTimestamp(i++, Timestamp.from(po.getWindowStart()));
            ps.setTimestamp(i++, Timestamp.from(po.getWindowEnd()));
            ps.setBigDecimal(i++, po.getPassMinElevationDeg());
            ps.setLong(i++, po.getPlannedBytes());
            ps.setBigDecimal(i++, po.getStationMinElevationDeg());
            ps.setLong(i++, po.getStationBytesPerMinute());
            ps.setString(i++, po.getStatus());
            ps.setString(i++, po.getPendingReason());
            if (po.getActualBytes() == null) {
                ps.setNull(i++, java.sql.Types.BIGINT);
            } else {
                ps.setLong(i++, po.getActualBytes());
            }
            ps.setString(i++, po.getCancelReason());
            ps.setTimestamp(i++, po.getConfirmedAt() == null ? null : Timestamp.from(po.getConfirmedAt()));
            ps.setTimestamp(i++, po.getReceivedAt() == null ? null : Timestamp.from(po.getReceivedAt()));
            ps.setTimestamp(i++, po.getCancelledAt() == null ? null : Timestamp.from(po.getCancelledAt()));
            ps.setTimestamp(i++, Timestamp.from(po.getCreatedAt()));
            ps.setTimestamp(i, Timestamp.from(po.getUpdatedAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按主键查。 */
    public Optional<SlotAllocationPo> findById(long id) {
        return jdbcTemplate.query("SELECT * FROM slot_allocation WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 按幂等 requestId 查。 */
    public Optional<SlotAllocationPo> findByRequestId(String requestId) {
        return jdbcTemplate.query("SELECT * FROM slot_allocation WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /** 事务内按主键加行锁。 */
    public Optional<SlotAllocationPo> lockById(long id) {
        return jdbcTemplate.query("SELECT * FROM slot_allocation WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询同天线与给定窗口重叠的已确认时隙（行锁），用于分配/重分配时的天线冲突判定。
     *
     * @param excludeId 重分配时排除自身的时隙 ID，首次分配传 {@code null}
     */
    public List<SlotAllocationPo> lockConfirmedOverlapping(String antennaCode,
                                                           Instant windowStart, Instant windowEnd,
                                                           Long excludeId) {
        String sql = "SELECT * FROM slot_allocation WHERE antenna_code = ? AND status = 'CONFIRMED' "
                + "AND window_start < ? AND ? < window_end ";
        if (excludeId == null) {
            sql += "ORDER BY id";
            return jdbcTemplate.query(sql, MAPPER, antennaCode,
                    Timestamp.from(windowEnd), Timestamp.from(windowStart));
        }
        sql += "AND id <> ? ORDER BY id";
        return jdbcTemplate.query(sql, MAPPER, antennaCode,
                Timestamp.from(windowEnd), Timestamp.from(windowStart), excludeId);
    }

    /**
     * 条件状态迁移：仅当当前状态为 {@code expectedStatus} 时才更新，保证终态唯一。
     *
     * @return 受影响行数，0 表示状态已被并发事务改变
     */
    public int compareAndSetStatus(long id, String expectedStatus, String newStatus, Instant now) {
        return jdbcTemplate.update(
                "UPDATE slot_allocation SET status = ?, updated_at = ? "
                        + "WHERE id = ? AND status = ?",
                newStatus, Timestamp.from(now), id, expectedStatus);
    }

    /** 标记为已接收（仅当前为 CONFIRMED 生效）。 */
    public int markReceived(long id, long actualBytes, Instant now) {
        return jdbcTemplate.update(
                "UPDATE slot_allocation SET status = 'RECEIVED', actual_bytes = ?, received_at = ?, "
                        + "updated_at = ? WHERE id = ? AND status = 'CONFIRMED'",
                actualBytes, Timestamp.from(now), Timestamp.from(now), id);
    }

    /** 标记为已取消（仅当前为 CONFIRMED 生效）。 */
    public int markCancelled(long id, String reason, Instant now) {
        return jdbcTemplate.update(
                "UPDATE slot_allocation SET status = 'CANCELLED', cancel_reason = ?, cancelled_at = ?, "
                        + "updated_at = ? WHERE id = ? AND status = 'CONFIRMED'",
                reason, Timestamp.from(now), Timestamp.from(now), id);
    }

    /**
     * 预测修订后将 CONFIRMED 时隙标记为待重新分配并释放天线（antenna_code 置空）。
     *
     * @param reason 可区分的待重排原因码
     */
    public int markPendingReassign(long id, String reason, Instant now) {
        return jdbcTemplate.update(
                "UPDATE slot_allocation SET status = 'PENDING_REASSIGN', pending_reason = ?, "
                        + "antenna_code = NULL, station_id = 0, station_code = NULL, "
                        + "confirmed_at = NULL, updated_at = ? WHERE id = ? AND status = 'CONFIRMED'",
                reason, Timestamp.from(now), id);
    }

    /**
     * 待重排时隙重新确认到新站点/天线，固化最新预测版本与容量快照（仅 PENDING_REASSIGN 生效）。
     */
    public int confirmReassign(long id, long stationId, String stationCode, String antennaCode,
                               long predictionId, int revisionNo,
                               BigDecimal passMinElevation, long plannedBytes,
                               BigDecimal stationMinElevation, long bytesPerMinute,
                               Instant windowStart, Instant windowEnd, Instant now) {
        return jdbcTemplate.update(
                "UPDATE slot_allocation SET station_id = ?, station_code = ?, antenna_code = ?, "
                        + "prediction_id = ?, prediction_revision_no = ?, window_start = ?, window_end = ?, "
                        + "pass_min_elevation_deg = ?, planned_bytes = ?, station_min_elevation_deg = ?, "
                        + "station_bytes_per_minute = ?, status = 'CONFIRMED', pending_reason = NULL, "
                        + "confirmed_at = ?, updated_at = ? WHERE id = ? AND status = 'PENDING_REASSIGN'",
                stationId, stationCode, antennaCode, predictionId, revisionNo,
                Timestamp.from(windowStart), Timestamp.from(windowEnd),
                passMinElevation, plannedBytes, stationMinElevation, bytesPerMinute,
                Timestamp.from(now), Timestamp.from(now), id);
    }

    /** 列出某卫星全部时隙，按窗口起点排序（时间线）。 */
    public List<SlotAllocationPo> findBySatellite(String satelliteCode) {
        return jdbcTemplate.query(
                "SELECT * FROM slot_allocation WHERE satellite_code = ? ORDER BY window_start, id",
                MAPPER, satelliteCode);
    }

    /** 列出某状态的全部时隙（修订扫描用），并对行加锁。 */
    public List<SlotAllocationPo> lockByStatus(String status) {
        return jdbcTemplate.query(
                "SELECT * FROM slot_allocation WHERE status = ? ORDER BY id FOR UPDATE", MAPPER, status);
    }

    /** 列出某状态的全部时隙（只读查询，不加锁）。 */
    public List<SlotAllocationPo> findByStatus(String status) {
        return jdbcTemplate.query(
                "SELECT * FROM slot_allocation WHERE status = ? ORDER BY window_start, id",
                MAPPER, status);
    }

    /** 列出某卫星指定状态的时隙并对行加锁（预测修订扫描用）。 */
    public List<SlotAllocationPo> lockBySatelliteAndStatus(String satelliteCode, String status) {
        return jdbcTemplate.query(
                "SELECT * FROM slot_allocation WHERE satellite_code = ? AND status = ? ORDER BY id FOR UPDATE",
                MAPPER, satelliteCode, status);
    }

    /**
     * 预测修订后仍满足新窗口的未开始时隙，将其固化快照推进到最新预测版本（保持 CONFIRMED）。
     */
    public int refreshPredictionSnapshot(long id, long predictionId, int revisionNo,
                                         BigDecimal passMinElevation, long plannedBytes, Instant now) {
        return jdbcTemplate.update(
                "UPDATE slot_allocation SET prediction_id = ?, prediction_revision_no = ?, "
                        + "pass_min_elevation_deg = ?, planned_bytes = ?, updated_at = ? "
                        + "WHERE id = ? AND status = 'CONFIRMED'",
                predictionId, revisionNo, passMinElevation, plannedBytes, Timestamp.from(now), id);
    }
}
