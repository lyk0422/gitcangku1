package com.example.starter.repo;

import com.example.starter.domain.Point;
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

    /** 按 routeId 查询航线（含当前版本点列与巡航参数），不存在返回 null。 */
    public RoutePo findRoute(String routeId) {
        try {
            return jdbc.queryForObject(
                    "SELECT version, cruise_altitude_m, start_at, end_at FROM route WHERE route_id = ?",
                    (rs, n) -> new RoutePo(routeId, rs.getInt("version"), findPoints(routeId),
                            rs.getInt("cruise_altitude_m"), rs.getLong("start_at"),
                            rs.getLong("end_at")),
                    routeId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
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

    private List<Point> findPoints(String routeId) {
        return jdbc.query(
                "SELECT x, y FROM route_point WHERE route_id = ? ORDER BY seq",
                (rs, n) -> new Point(rs.getInt("x"), rs.getInt("y")), routeId);
    }

    /** 创建航线（初始版本 1）并写入点列与巡航参数（调用方负责事务）。 */
    public void insertRoute(String routeId, List<Point> points,
                            int cruiseAltitudeM, long startAt, long endAt) {
        jdbc.update("INSERT INTO route (route_id, version, touch, cruise_altitude_m, start_at, end_at) "
                        + "VALUES (?, 1, 0, ?, ?, ?)",
                routeId, cruiseAltitudeM, startAt, endAt);
        insertPoints(routeId, points);
    }

    /**
     * 条件更新航线版本：仅当当前版本等于 expectedVersion 时加一（同时推进 touch
     * 以持有行写锁，与审核事务互斥），并更新巡航参数。
     *
     * @return 更新行数；0 表示版本不匹配
     */
    public int compareAndIncrementVersion(String routeId, int expectedVersion,
                                          int cruiseAltitudeM, long startAt, long endAt) {
        return jdbc.update(
                "UPDATE route SET version = version + 1, touch = touch + 1, "
                        + "cruise_altitude_m = ?, start_at = ?, end_at = ? "
                        + "WHERE route_id = ? AND version = ?",
                cruiseAltitudeM, startAt, endAt, routeId, expectedVersion);
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
}
