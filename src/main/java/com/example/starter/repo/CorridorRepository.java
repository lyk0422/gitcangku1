package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 应急走廊与让渡历史数据访问。让渡历史不可变，只插入不更新。
 */
@Repository
public class CorridorRepository {

    private final JdbcTemplate jdbc;

    public CorridorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String CORRIDOR_COLUMNS =
            "corridor_id, x_min, y_min, x_max, y_max, start_at, end_at, "
                    + "altitude_lower, altitude_upper, priority, status, dispatcher_id, reason, "
                    + "created_version, revoked_version, created_at, revoked_at";

    private static final RowMapper<CorridorPo> CORRIDOR_MAPPER = (rs, n) -> new CorridorPo(
            rs.getString("corridor_id"),
            rs.getInt("x_min"), rs.getInt("y_min"), rs.getInt("x_max"), rs.getInt("y_max"),
            rs.getLong("start_at"), rs.getLong("end_at"),
            rs.getInt("altitude_lower"), rs.getInt("altitude_upper"),
            rs.getInt("priority"), rs.getString("status"), rs.getString("dispatcher_id"),
            rs.getString("reason"), rs.getLong("created_version"),
            (Long) rs.getObject("revoked_version"),
            rs.getLong("created_at"), (Long) rs.getObject("revoked_at"));

    private static final RowMapper<CorridorYieldPo> YIELD_MAPPER = (rs, n) -> new CorridorYieldPo(
            rs.getString("yield_id"), rs.getString("corridor_id"), rs.getLong("corridor_version"),
            rs.getString("occupancy_id"), rs.getString("route_id"), rs.getString("review_id"),
            rs.getInt("route_version"), rs.getLong("airspace_version"),
            rs.getString("reason"), rs.getLong("created_at"));

    /** 按 corridorId 查询走廊，不存在返回 null。 */
    public CorridorPo findCorridor(String corridorId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + CORRIDOR_COLUMNS + " FROM emergency_corridor WHERE corridor_id = ?",
                    CORRIDOR_MAPPER, corridorId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 查询与指定时空（闭矩形 + 左闭右开时段与高度）重叠的同优先级有效走廊。
     * 有效指状态 ACTIVE 且尚未自然到期（end_at 大于参考时刻）。
     * 结果按 corridorId 排序，保证判定与响应稳定。
     */
    public List<CorridorPo> findOverlappingActiveCorridors(int priority, long now,
                                                           int xMin, int yMin, int xMax, int yMax,
                                                           long startAt, long endAt,
                                                           int altitudeLower, int altitudeUpper) {
        return jdbc.query(
                "SELECT " + CORRIDOR_COLUMNS + " FROM emergency_corridor "
                        + "WHERE status = 'ACTIVE' AND priority = ? AND end_at > ? "
                        + "AND x_min <= ? AND x_max >= ? AND y_min <= ? AND y_max >= ? "
                        + "AND start_at < ? AND end_at > ? "
                        + "AND altitude_lower < ? AND altitude_upper > ? "
                        + "ORDER BY corridor_id",
                CORRIDOR_MAPPER, priority, now,
                xMax, xMin, yMax, yMin,
                endAt, startAt,
                altitudeUpper, altitudeLower);
    }

    /**
     * 查询当前时刻处于生效时间窗内的 ACTIVE 走廊（用于常规航线审查的走廊命中判定）。
     * 结果按 corridorId 排序。
     */
    public List<CorridorPo> findEffectiveCorridorsAt(long now) {
        return jdbc.query(
                "SELECT " + CORRIDOR_COLUMNS + " FROM emergency_corridor "
                        + "WHERE status = 'ACTIVE' AND start_at <= ? AND end_at > ? "
                        + "ORDER BY corridor_id",
                CORRIDOR_MAPPER, now, now);
    }

    /**
     * 查询与指定左闭右开时段和高度范围重叠的 ACTIVE 走廊（用于常规占用判定）。
     * 有效指状态 ACTIVE 且尚未自然到期（end_at 大于参考时刻）；
     * 空间相交由调用方用占用航点快照与走廊区域判定。结果按 corridorId 排序。
     */
    public List<CorridorPo> findActiveCorridorsOverlappingWindow(long now,
                                                                 long startAt, long endAt,
                                                                 int altitudeLower, int altitudeUpper) {
        return jdbc.query(
                "SELECT " + CORRIDOR_COLUMNS + " FROM emergency_corridor "
                        + "WHERE status = 'ACTIVE' AND end_at > ? "
                        + "AND start_at < ? AND end_at > ? "
                        + "AND altitude_lower < ? AND altitude_upper > ? "
                        + "ORDER BY corridor_id",
                CORRIDOR_MAPPER, now,
                endAt, startAt,
                altitudeUpper, altitudeLower);
    }

    /** 查询全部 ACTIVE 且尚未自然到期的走廊（诊断用），按 corridorId 排序。 */
    public List<CorridorPo> findActiveCorridors(long now) {
        return jdbc.query(
                "SELECT " + CORRIDOR_COLUMNS + " FROM emergency_corridor "
                        + "WHERE status = 'ACTIVE' AND end_at > ? ORDER BY corridor_id",
                CORRIDOR_MAPPER, now);
    }

    /** 插入走廊（调用方负责事务与版本递增）。 */
    public void insertCorridor(CorridorPo po) {
        jdbc.update("INSERT INTO emergency_corridor ("
                        + "corridor_id, x_min, y_min, x_max, y_max, start_at, end_at, "
                        + "altitude_lower, altitude_upper, priority, status, dispatcher_id, reason, "
                        + "created_version, revoked_version, created_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.corridorId(), po.xMin(), po.yMin(), po.xMax(), po.yMax(),
                po.startAt(), po.endAt(), po.altitudeLower(), po.altitudeUpper(),
                po.priority(), po.status(), po.dispatcherId(), po.reason(),
                po.createdVersion(), po.revokedVersion(), po.createdAt(), po.revokedAt());
    }

    /** 撤销走廊并记录撤销版本与时间（调用方负责事务与版本递增）。 */
    public void markRevoked(String corridorId, long revokedVersion, long revokedAt) {
        jdbc.update("UPDATE emergency_corridor SET status = 'REVOKED', revoked_version = ?, "
                        + "revoked_at = ? WHERE corridor_id = ? AND status = 'ACTIVE'",
                revokedVersion, revokedAt, corridorId);
    }

    /** 插入不可变让渡记录（调用方负责事务）。 */
    public void insertYield(CorridorYieldPo po) {
        jdbc.update("INSERT INTO corridor_yield ("
                        + "yield_id, corridor_id, corridor_version, occupancy_id, route_id, "
                        + "review_id, route_version, airspace_version, reason, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.yieldId(), po.corridorId(), po.corridorVersion(), po.occupancyId(), po.routeId(),
                po.reviewId(), po.routeVersion(), po.airspaceVersion(), po.reason(), po.createdAt());
    }

    /** 查询某走廊的全部让渡记录，按让渡时间、yieldId 排序（稳定顺序）。 */
    public List<CorridorYieldPo> findYieldsByCorridor(String corridorId) {
        return jdbc.query(
                "SELECT yield_id, corridor_id, corridor_version, occupancy_id, route_id, "
                        + "review_id, route_version, airspace_version, reason, created_at "
                        + "FROM corridor_yield WHERE corridor_id = ? "
                        + "ORDER BY created_at, yield_id",
                YIELD_MAPPER, corridorId);
    }

    /** 按占用查询让渡记录（一个占用至多一条），不存在返回 null。 */
    public CorridorYieldPo findYieldByOccupancy(String occupancyId) {
        return jdbc.query(
                "SELECT yield_id, corridor_id, corridor_version, occupancy_id, route_id, "
                        + "review_id, route_version, airspace_version, reason, created_at "
                        + "FROM corridor_yield WHERE occupancy_id = ?",
                YIELD_MAPPER, occupancyId).stream().findFirst().orElse(null);
    }
}
