package com.example.starter.repo;

import com.example.starter.domain.Point;
import com.example.starter.domain.TimeWindow;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 航线与航点数据访问。
 */
@Repository
public class RouteRepository {

    private final JdbcTemplate jdbc;

    public RouteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按 routeId 查询航线（含当前版本点列与窗口），不存在返回 null。 */
    public RoutePo findRoute(String routeId) {
        Integer version;
        TimeWindow window;
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT version, window_start, window_end FROM route WHERE route_id = ?",
                    routeId);
            version = (Integer) row.get("version");
            window = toWindow(row.get("window_start"), row.get("window_end"));
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
        if (version == null) {
            return null;
        }
        List<Point> points = findPoints(routeId);
        return new RoutePo(routeId, version, points, window);
    }

    /**
     * 在当前事务内对航线行做真实更新（touch 加一）取得行级排他锁，并读取航线（含点列与窗口）。
     *
     * <p>更新为不同的值确保 H2/MySQL 不跳过加锁；与替换操作的行更新互斥，
     * 保证审核读到的航线版本、点列与窗口来自一致状态。</p>
     *
     * @return 航线当前状态；航线不存在返回 null（更新 0 行）
     */
    public RoutePo findRouteForUpdate(String routeId) {
        int locked = jdbc.update(
                "UPDATE route SET touch = touch + 1 WHERE route_id = ?", routeId);
        if (locked == 0) {
            return null;
        }
        Integer version;
        TimeWindow window;
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT version, window_start, window_end FROM route WHERE route_id = ?",
                    routeId);
            version = (Integer) row.get("version");
            window = toWindow(row.get("window_start"), row.get("window_end"));
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
        if (version == null) {
            return null;
        }
        List<Point> points = findPoints(routeId);
        return new RoutePo(routeId, version, points, window);
    }

    /** 成对窗口列转 TimeWindow；两端均为 NULL 表示全时。 */
    private static TimeWindow toWindow(Object start, Object end) {
        Long s = start == null ? null : ((Number) start).longValue();
        Long e = end == null ? null : ((Number) end).longValue();
        if (s == null && e == null) {
            return null;
        }
        return new TimeWindow(s == null ? 0L : s, e == null ? 0L : e);
    }

    private List<Point> findPoints(String routeId) {
        return jdbc.query(
                "SELECT x, y FROM route_point WHERE route_id = ? ORDER BY seq",
                (rs, n) -> new Point(rs.getInt("x"), rs.getInt("y")), routeId);
    }

    /** 创建航线（初始版本 1）并写入点列与窗口（调用方负责事务）。 */
    public void insertRoute(String routeId, List<Point> points, TimeWindow window) {
        jdbc.update("INSERT INTO route (route_id, version, touch, window_start, window_end) "
                        + "VALUES (?, 1, 0, ?, ?)",
                routeId, window == null ? null : window.startUtcMillis(),
                window == null ? null : window.endUtcMillis());
        insertPoints(routeId, points);
    }

    /**
     * 条件更新航线版本：仅当当前版本等于 expectedVersion 时加一（同时推进 touch
     * 以持有行写锁，与审核事务互斥），并整体替换窗口（null 明确为全时）。
     *
     * @return 更新行数；0 表示版本不匹配
     */
    public int compareAndIncrementVersion(String routeId, int expectedVersion, TimeWindow window) {
        return jdbc.update(
                "UPDATE route SET version = version + 1, touch = touch + 1, "
                        + "window_start = ?, window_end = ? "
                        + "WHERE route_id = ? AND version = ?",
                window == null ? null : window.startUtcMillis(),
                window == null ? null : window.endUtcMillis(),
                routeId, expectedVersion);
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
