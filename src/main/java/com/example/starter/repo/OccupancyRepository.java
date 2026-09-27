package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 空域时空占用数据访问。占用状态只允许 ACTIVE → YIELDED 与起飞登记两类受控变更。
 */
@Repository
public class OccupancyRepository {

    private final JdbcTemplate jdbc;

    public OccupancyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String OCCUPANCY_COLUMNS =
            "occupancy_id, route_id, route_version, route_type, start_at, end_at, "
                    + "altitude_lower, altitude_upper, yieldable, status, departed, departed_at, "
                    + "review_id, points_snapshot, request_id, created_at";

    private static final RowMapper<OccupancyPo> OCCUPANCY_MAPPER = (rs, n) -> new OccupancyPo(
            rs.getString("occupancy_id"),
            rs.getString("route_id"), rs.getInt("route_version"), rs.getString("route_type"),
            rs.getLong("start_at"), rs.getLong("end_at"),
            rs.getInt("altitude_lower"), rs.getInt("altitude_upper"),
            rs.getBoolean("yieldable"), rs.getString("status"), rs.getBoolean("departed"),
            (Long) rs.getObject("departed_at"),
            rs.getString("review_id"),
            ReviewRepository.decodePoints(rs.getString("points_snapshot")),
            rs.getString("request_id"), rs.getLong("created_at"));

    /** 按 occupancyId 查询占用，不存在返回 null。 */
    public OccupancyPo findOccupancy(String occupancyId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + OCCUPANCY_COLUMNS + " FROM occupancy WHERE occupancy_id = ?",
                    OCCUPANCY_MAPPER, occupancyId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 查询与指定时空（左闭右开时段与高度）重叠的常规 ACTIVE 占用。
     * 空间相交由调用方用占用航点快照与走廊区域判定。结果按 occupancyId 排序。
     */
    public List<OccupancyPo> findActiveRegularOverlapping(long startAt, long endAt,
                                                          int altitudeLower, int altitudeUpper) {
        return jdbc.query(
                "SELECT " + OCCUPANCY_COLUMNS + " FROM occupancy "
                        + "WHERE status = 'ACTIVE' AND route_type = 'REGULAR' "
                        + "AND start_at < ? AND end_at > ? "
                        + "AND altitude_lower < ? AND altitude_upper > ? "
                        + "ORDER BY occupancy_id",
                OCCUPANCY_MAPPER, endAt, startAt, altitudeUpper, altitudeLower);
    }

    /** 查询某航线的全部占用历史，按创建时间、occupancyId 排序（稳定顺序）。 */
    public List<OccupancyPo> findByRoute(String routeId) {
        return jdbc.query(
                "SELECT " + OCCUPANCY_COLUMNS + " FROM occupancy WHERE route_id = ? "
                        + "ORDER BY created_at, occupancy_id",
                OCCUPANCY_MAPPER, routeId);
    }

    /** 查询全部常规 ACTIVE 占用（诊断用），按 occupancyId 排序。 */
    public List<OccupancyPo> findActiveRegular() {
        return jdbc.query(
                "SELECT " + OCCUPANCY_COLUMNS + " FROM occupancy "
                        + "WHERE status = 'ACTIVE' AND route_type = 'REGULAR' "
                        + "ORDER BY occupancy_id",
                OCCUPANCY_MAPPER);
    }

    /** 插入占用（调用方负责事务）。 */
    public void insertOccupancy(OccupancyPo po) {
        jdbc.update("INSERT INTO occupancy ("
                        + "occupancy_id, route_id, route_version, route_type, start_at, end_at, "
                        + "altitude_lower, altitude_upper, yieldable, status, departed, departed_at, "
                        + "review_id, points_snapshot, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.occupancyId(), po.routeId(), po.routeVersion(), po.routeType(),
                po.startAt(), po.endAt(), po.altitudeLower(), po.altitudeUpper(),
                po.yieldable(), po.status(), po.departed(), po.departedAt(),
                po.reviewId(), ReviewRepository.encodePoints(po.pointsSnapshot()),
                po.requestId(), po.createdAt());
    }

    /** 将 ACTIVE 占用标记为 YIELDED（调用方负责事务；条件更新防止并发重复让渡）。 */
    public int markYielded(String occupancyId) {
        return jdbc.update("UPDATE occupancy SET status = 'YIELDED' "
                + "WHERE occupancy_id = ? AND status = 'ACTIVE'", occupancyId);
    }

    /** 将 ACTIVE 占用登记为已起飞（调用方负责事务；条件更新防止并发重复登记）。 */
    public int markDeparted(String occupancyId, long departedAt) {
        return jdbc.update("UPDATE occupancy SET departed = TRUE, departed_at = ? "
                + "WHERE occupancy_id = ? AND status = 'ACTIVE' AND departed = FALSE",
                departedAt, occupancyId);
    }
}
