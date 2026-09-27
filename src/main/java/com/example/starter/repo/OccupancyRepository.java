package com.example.starter.repo;

import com.example.starter.domain.Point;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 空域占用、让渡历史与处置事件数据访问。
 */
@Repository
public class OccupancyRepository {

    private final JdbcTemplate jdbc;

    public OccupancyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String OCC_COLUMNS = "occupancy_id, route_id, review_id, route_version, "
            + "airspace_version, kind, start_utc, end_utc, alt_min, alt_max, status, yieldable, "
            + "departed, corridor_id, parent_occupancy_id, points_snapshot, created_at, departed_at";

    private static final RowMapper<OccupancyPo> OCC_MAPPER = (rs, n) -> new OccupancyPo(
            rs.getString("occupancy_id"),
            rs.getString("route_id"),
            rs.getString("review_id"),
            rs.getInt("route_version"),
            rs.getLong("airspace_version"),
            rs.getString("kind"),
            rs.getLong("start_utc"),
            rs.getLong("end_utc"),
            rs.getInt("alt_min"),
            rs.getInt("alt_max"),
            rs.getString("status"),
            rs.getBoolean("yieldable"),
            rs.getBoolean("departed"),
            rs.getString("corridor_id"),
            rs.getString("parent_occupancy_id"),
            ReviewRepository.decodePoints(rs.getString("points_snapshot")),
            rs.getLong("created_at"),
            (Long) rs.getObject("departed_at"));

    private static final RowMapper<YieldRecordPo> YIELD_MAPPER = (rs, n) -> new YieldRecordPo(
            rs.getLong("seq"),
            rs.getString("occupancy_id"),
            rs.getString("corridor_id"),
            rs.getLong("corridor_version"),
            rs.getString("review_id"),
            rs.getInt("route_version"),
            rs.getLong("airspace_version"),
            rs.getString("reason"),
            rs.getLong("created_at"));

    private static final RowMapper<OccupancyEventPo> EVENT_MAPPER = (rs, n) -> new OccupancyEventPo(
            rs.getLong("seq"),
            rs.getString("occupancy_id"),
            rs.getString("event_type"),
            rs.getLong("event_time"),
            rs.getString("corridor_id"),
            rs.getString("related_occupancy_id"),
            rs.getString("review_id"),
            rs.getString("reason"));

    /** 按 occupancyId 查询占用，不存在返回 null。 */
    public OccupancyPo findOccupancy(String occupancyId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + OCC_COLUMNS + " FROM occupancy WHERE occupancy_id = ?",
                    OCC_MAPPER, occupancyId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 查询全部 ACTIVE 常规占用（含可让渡与不可让渡、已起飞与未起飞）。
     * 调用方必须已持有 coord_lock 协调锁：占用登记与起飞登记在插入/更新前
     * 同样先更新该锁行，因此本读取不会看到尚未提交的并发占用。
     * 时空相交与处置裁决由 service 在锁保护下完成。
     */
    public List<OccupancyPo> findActiveRegularOccupancies() {
        return jdbc.query(
                "SELECT " + OCC_COLUMNS
                        + " FROM occupancy WHERE status = 'ACTIVE' AND kind = 'REGULAR' "
                        + "ORDER BY occupancy_id",
                OCC_MAPPER);
    }

    /** 查询全部占用（历史/诊断用），按 occupancyId 升序。 */
    public List<OccupancyPo> findAll() {
        return jdbc.query(
                "SELECT " + OCC_COLUMNS + " FROM occupancy ORDER BY occupancy_id", OCC_MAPPER);
    }

    /** 登记占用（调用方负责事务）。 */
    public void insertOccupancy(OccupancyPo po) {
        jdbc.update("INSERT INTO occupancy "
                        + "(occupancy_id, route_id, review_id, route_version, airspace_version, "
                        + "kind, start_utc, end_utc, alt_min, alt_max, status, yieldable, departed, "
                        + "corridor_id, parent_occupancy_id, points_snapshot, created_at, departed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.occupancyId(), po.routeId(), po.reviewId(), po.routeVersion(),
                po.airspaceVersion(), po.kind(), po.startUtc(), po.endUtc(), po.altMin(),
                po.altMax(), po.status(), po.yieldable(), po.departed(), po.corridorId(),
                po.parentOccupancyId(), ReviewRepository.encodePoints(po.pointsSnapshot()),
                po.createdAt(), po.departedAt());
    }

    /**
     * 在走廊创建事务内让渡占用：仅 ACTIVE 且未起飞可更新。
     *
     * @return 更新行数；0 表示状态已变化（被并发事务抢先处置，走廊事务须回滚 422）
     */
    public int markYielded(String occupancyId, String corridorId) {
        return jdbc.update(
                "UPDATE occupancy SET status = 'YIELDED', corridor_id = ? "
                        + "WHERE occupancy_id = ? AND status = 'ACTIVE' AND departed = FALSE",
                corridorId, occupancyId);
    }

    /**
     * 起飞登记：仅 ACTIVE 且未起飞可更新。
     *
     * @return 更新行数；0 表示不存在、已让渡或已起飞
     */
    public int markDeparted(String occupancyId, long departedAt) {
        return jdbc.update(
                "UPDATE occupancy SET departed = TRUE, departed_at = ? "
                        + "WHERE occupancy_id = ? AND status = 'ACTIVE' AND departed = FALSE",
                departedAt, occupancyId);
    }

    /** 追加让渡不可变历史（调用方负责事务）。 */
    public void insertYieldRecord(YieldRecordPo po) {
        jdbc.update("INSERT INTO yield_record "
                        + "(occupancy_id, corridor_id, corridor_version, review_id, route_version, "
                        + "airspace_version, reason, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                po.occupancyId(), po.corridorId(), po.corridorVersion(), po.reviewId(),
                po.routeVersion(), po.airspaceVersion(), po.reason(), po.createdAt());
    }

    /** 按走廊查询让渡历史，固定按 seq 升序。 */
    public List<YieldRecordPo> findYieldRecordsByCorridor(String corridorId) {
        return jdbc.query(
                "SELECT seq, occupancy_id, corridor_id, corridor_version, review_id, "
                        + "route_version, airspace_version, reason, created_at "
                        + "FROM yield_record WHERE corridor_id = ? ORDER BY seq",
                YIELD_MAPPER, corridorId);
    }

    /** 按原占用查询让渡历史（至多一条），固定按 seq 升序。 */
    public List<YieldRecordPo> findYieldRecordsByOccupancy(String occupancyId) {
        return jdbc.query(
                "SELECT seq, occupancy_id, corridor_id, corridor_version, review_id, "
                        + "route_version, airspace_version, reason, created_at "
                        + "FROM yield_record WHERE occupancy_id = ? ORDER BY seq",
                YIELD_MAPPER, occupancyId);
    }

    /** 查询全部让渡历史，固定按 seq 升序。 */
    public List<YieldRecordPo> findAllYieldRecords() {
        return jdbc.query(
                "SELECT seq, occupancy_id, corridor_id, corridor_version, review_id, "
                        + "route_version, airspace_version, reason, created_at "
                        + "FROM yield_record ORDER BY seq",
                YIELD_MAPPER);
    }

    /** 追加占用处置不可变事件（调用方负责事务）。 */
    public void insertEvent(OccupancyEventPo po) {
        jdbc.update("INSERT INTO occupancy_event "
                        + "(occupancy_id, event_type, event_time, corridor_id, "
                        + "related_occupancy_id, review_id, reason) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                po.occupancyId(), po.eventType(), po.eventTime(), po.corridorId(),
                po.relatedOccupancyId(), po.reviewId(), po.reason());
    }

    /** 查询某占用（含重新申请关联的原占用）事件，固定按 seq 升序。 */
    public List<OccupancyEventPo> findEventsByOccupancy(String occupancyId) {
        return jdbc.query(
                "SELECT seq, occupancy_id, event_type, event_time, corridor_id, "
                        + "related_occupancy_id, review_id, reason "
                        + "FROM occupancy_event WHERE occupancy_id = ? ORDER BY seq",
                EVENT_MAPPER, occupancyId);
    }
}
