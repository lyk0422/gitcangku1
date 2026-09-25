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

    /** 按 routeId 查询航线（含当前版本点列），不存在返回 null。 */
    public RoutePo findRoute(String routeId) {
        RoutePo row = findRouteRow(routeId);
        if (row == null) {
            return null;
        }
        return new RoutePo(row.routeId(), row.version(), findPoints(routeId),
                row.status(), row.priority(), row.eventNo());
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

    private RoutePo findRouteRow(String routeId) {
        try {
            return jdbc.queryForObject(
                    "SELECT route_id, version, status, priority, event_no FROM route WHERE route_id = ?",
                    (rs, n) -> new RoutePo(rs.getString("route_id"), rs.getInt("version"), List.of(),
                            rs.getString("status"), rs.getString("priority"), rs.getString("event_no")),
                    routeId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 查询指定状态的全部航线（按 routeId 字典序）。 */
    public List<RoutePo> findRoutesByStatus(String status) {
        return jdbc.query(
                "SELECT route_id, version, status, priority, event_no FROM route "
                        + "WHERE status = ? ORDER BY route_id",
                (rs, n) -> new RoutePo(rs.getString("route_id"), rs.getInt("version"), List.of(),
                        rs.getString("status"), rs.getString("priority"), rs.getString("event_no")),
                status);
    }

    private List<Point> findPoints(String routeId) {
        return jdbc.query(
                "SELECT x, y FROM route_point WHERE route_id = ? ORDER BY seq",
                (rs, n) -> new Point(rs.getInt("x"), rs.getInt("y")), routeId);
    }

    /** 创建航线（初始版本 1，状态 PENDING）并写入点列（调用方负责事务）。 */
    public void insertRoute(String routeId, List<Point> points) {
        jdbc.update("INSERT INTO route (route_id, version, touch, status, priority, event_no) "
                + "VALUES (?, 1, 0, 'PENDING', 'NORMAL', NULL)", routeId);
        insertPoints(routeId, points);
    }

    /**
     * 条件更新航线版本：仅当当前版本等于 expectedVersion 时加一（同时推进 touch
     * 以持有行写锁，与审核事务互斥）。
     *
     * @return 更新行数；0 表示版本不匹配
     */
    public int compareAndIncrementVersion(String routeId, int expectedVersion) {
        return jdbc.update(
                "UPDATE route SET version = version + 1, touch = touch + 1 "
                        + "WHERE route_id = ? AND version = ?",
                routeId, expectedVersion);
    }

    /**
     * 审查批准后更新航线状态与优先级声明（调用方负责事务与行锁）。
     * DEPARTED 航线不被回退：仅当当前状态不是 DEPARTED 时更新。
     *
     * @return 更新行数
     */
    public int markReviewed(String routeId, String newStatus, String priority, String eventNo) {
        return jdbc.update(
                "UPDATE route SET status = ?, priority = ?, event_no = ? "
                        + "WHERE route_id = ? AND status <> 'DEPARTED'",
                newStatus, priority, eventNo, routeId);
    }

    /**
     * 条件状态迁移：仅当当前状态等于 expectedStatus 时迁移到 newStatus。
     * 用于抢占置换（APPROVED → DISPLACED）与起飞登记（APPROVED → DEPARTED）
     * 的状态不符检测，更新 0 行即状态不符，调用方应回滚事务。
     *
     * @return 更新行数；0 表示状态不符
     */
    public int transitionStatus(String routeId, String expectedStatus, String newStatus) {
        return jdbc.update(
                "UPDATE route SET status = ? WHERE route_id = ? AND status = ?",
                newStatus, routeId, expectedStatus);
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
