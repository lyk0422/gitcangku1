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

    /** 按 routeId 查询航线（含当前版本点列与飞行窗口），不存在返回 null。 */
    public RoutePo findRoute(String routeId) {
        try {
            return jdbc.queryForObject(
                    "SELECT version, window_start, window_end FROM route WHERE route_id = ?",
                    (rs, n) -> new RoutePo(routeId, rs.getInt("version"), findPoints(routeId),
                            (Long) rs.getObject("window_start"),
                            (Long) rs.getObject("window_end")),
                    routeId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 在当前事务内对航线行做真实更新（touch 加一）取得行级排他锁，并读取航线（含点列与窗口）。
     *
     * <p>更新为不同的值确保 H2/MySQL 不跳过加锁；与替换操作的行更新互斥，
     * 保证审核读到的航线版本、点列与飞行窗口来自一致状态。</p>
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

    /** 创建航线（初始版本 1）并写入点列与飞行窗口（调用方负责事务）。 */
    public void insertRoute(String routeId, List<Point> points, Long windowStart, Long windowEnd) {
        jdbc.update("INSERT INTO route (route_id, version, touch, window_start, window_end) "
                + "VALUES (?, 1, 0, ?, ?)", routeId, windowStart, windowEnd);
        insertPoints(routeId, points);
    }

    /**
     * 条件更新航线版本与飞行窗口：仅当当前版本等于 expectedVersion 时加一（同时推进 touch
     * 以持有行写锁，与审核事务互斥）。窗口在每次替换时整体重写；
     * 调用方传入 null 对即明确设为全时。
     *
     * @return 更新行数；0 表示版本不匹配
     */
    public int compareAndIncrementVersion(String routeId, int expectedVersion,
                                          Long windowStart, Long windowEnd) {
        return jdbc.update(
                "UPDATE route SET version = version + 1, touch = touch + 1, "
                        + "window_start = ?, window_end = ? "
                        + "WHERE route_id = ? AND version = ?",
                windowStart, windowEnd, routeId, expectedVersion);
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
