package com.example.starter.repo;

import com.example.starter.domain.FlightPlan;
import com.example.starter.domain.Point;
import com.example.starter.domain.RouteCategory;
import com.example.starter.domain.RouteStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 航线与航点数据访问。
 */
@Repository
public class RouteRepository {

    private final JdbcTemplate jdbc;

    public RouteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按 routeId 查询航线（含当前版本点列与起降计划），不存在返回 null。 */
    public RoutePo findRoute(String routeId) {
        RouteRow row = findRouteRow(routeId);
        if (row == null) {
            return null;
        }
        return new RoutePo(routeId, row.version(), findPoints(routeId), row.status(), row.flightPlan());
    }

    /**
     * 在当前事务内对航线行做真实更新（touch 加一）取得行级排他锁，并读取航线（含点列）。
     *
     * <p>更新为不同的值确保 H2/MySQL 不跳过加锁；与替换操作的行更新互斥，
     * 保证审核读到的航线版本与点列来自一致状态。</p>
     *
     * @return 航线当前状态；航线不存在返回 null（更新 0 行）
     */
    public RoutePo findRouteForUpdate(String routeId) {
        int locked = jdbc.update(
                "UPDATE route SET touch = touch + 1 WHERE route_id = ?", routeId);
        if (locked == 0) {
            return null;
        }
        return findRoute(routeId);
    }

    private RouteRow findRouteRow(String routeId) {
        try {
            return jdbc.queryForObject(
                    "SELECT version, status, category, event_no, dep_runway_id, dep_time_utc, "
                            + "arr_runway_id, arr_time_utc FROM route WHERE route_id = ?",
                    (rs, n) -> new RouteRow(
                            rs.getInt("version"),
                            rs.getString("status"),
                            toFlightPlan(rs.getString("category"), rs.getString("event_no"),
                                    rs.getString("dep_runway_id"), (Long) rs.getObject("dep_time_utc"),
                                    rs.getString("arr_runway_id"), (Long) rs.getObject("arr_time_utc"))),
                    routeId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private static FlightPlan toFlightPlan(String category, String eventNo,
                                           String depRunwayId, Long depTimeUtc,
                                           String arrRunwayId, Long arrTimeUtc) {
        if (category == null && depRunwayId == null && arrRunwayId == null) {
            return null;
        }
        return new FlightPlan(category, eventNo, depRunwayId, depTimeUtc, arrRunwayId, arrTimeUtc);
    }

    private List<Point> findPoints(String routeId) {
        return jdbc.query(
                "SELECT x, y FROM route_point WHERE route_id = ? ORDER BY seq",
                (rs, n) -> new Point(rs.getInt("x"), rs.getInt("y")), routeId);
    }

    /** 创建航线（初始版本 1，状态 DRAFT）并写入点列与起降计划（调用方负责事务）。 */
    public void insertRoute(String routeId, List<Point> points, FlightPlan plan) {
        jdbc.update("INSERT INTO route (route_id, version, touch, status, category, event_no, "
                        + "dep_runway_id, dep_time_utc, arr_runway_id, arr_time_utc) "
                        + "VALUES (?, 1, 0, ?, ?, ?, ?, ?, ?, ?)",
                routeId, RouteStatus.DRAFT.name(),
                plan == null ? null : plan.category(),
                plan == null ? null : plan.eventNo(),
                plan == null ? null : plan.depRunwayId(),
                plan == null ? null : plan.depTimeUtc(),
                plan == null ? null : plan.arrRunwayId(),
                plan == null ? null : plan.arrTimeUtc());
        insertPoints(routeId, points);
    }

    /**
     * 条件更新航线版本：仅当当前版本等于 expectedVersion 时加一（同时推进 touch
     * 以持有行写锁，与审核事务互斥）；替换后状态回到 DRAFT 并写入新起降计划。
     *
     * @return 更新行数；0 表示版本不匹配
     */
    public int compareAndIncrementVersion(String routeId, int expectedVersion, FlightPlan plan) {
        return jdbc.update(
                "UPDATE route SET version = version + 1, touch = touch + 1, status = ?, "
                        + "category = ?, event_no = ?, dep_runway_id = ?, dep_time_utc = ?, "
                        + "arr_runway_id = ?, arr_time_utc = ? "
                        + "WHERE route_id = ? AND version = ?",
                RouteStatus.DRAFT.name(),
                plan == null ? null : plan.category(),
                plan == null ? null : plan.eventNo(),
                plan == null ? null : plan.depRunwayId(),
                plan == null ? null : plan.depTimeUtc(),
                plan == null ? null : plan.arrRunwayId(),
                plan == null ? null : plan.arrTimeUtc(),
                routeId, expectedVersion);
    }

    /** 更新航线状态（调用方负责事务与行锁）。 */
    public void updateStatus(String routeId, String status) {
        jdbc.update("UPDATE route SET status = ? WHERE route_id = ?", status, routeId);
    }

    /** 转为紧急例外：写入类别与事件号并恢复已批准状态（调用方负责事务与行锁）。 */
    public void convertToEmergency(String routeId, String eventNo) {
        jdbc.update("UPDATE route SET category = ?, event_no = ?, status = ? WHERE route_id = ?",
                RouteCategory.EMERGENCY.name(), eventNo,
                RouteStatus.APPROVED.name(), routeId);
    }

    /** 删除航线旧点列（调用方负责事务）。 */
    public void deletePoints(String routeId) {
        jdbc.update("DELETE FROM route_point WHERE route_id = ?", routeId);
    }

    /** 批量写入航线点列（调用方负责事务）。 */
    public void insertPoints(String routeId, List<Point> points) {
        int seq = 0;
        for (Point p : points) {
            jdbc.update("INSERT INTO route_point (route_id, seq, x, y) VALUES (?, ?, ?, ?)",
                    routeId, seq++, p.x(), p.y());
        }
    }

    /** 航线行内部表示（版本、状态与起降计划）。 */
    private record RouteRow(int version, String status, FlightPlan flightPlan) {
    }
}
