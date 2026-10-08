package com.example.starter.repo;

import com.example.starter.domain.CurveSegment;
import com.example.starter.domain.Point;
import com.example.starter.domain.RouteKind;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 航线、折线航点与二次曲线段数据访问。
 */
@Repository
public class RouteRepository {

    private final JdbcTemplate jdbc;

    public RouteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按 routeId 查询航线（含当前版本几何），不存在返回 null。 */
    public RoutePo findRoute(String routeId) {
        Row row;
        try {
            row = jdbc.queryForObject(
                    "SELECT version, kind FROM route WHERE route_id = ?",
                    (rs, n) -> new Row(rs.getInt("version"),
                            RouteKind.valueOf(rs.getString("kind"))), routeId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
        if (row == null) {
            return null;
        }
        return loadGeometry(routeId, row);
    }

    /**
     * 在当前事务内对航线行做真实更新（touch 加一）取得行级排他锁，并读取航线几何。
     *
     * <p>更新为不同的值确保 H2/MySQL 不跳过加锁；与替换操作的行更新互斥，
     * 保证审核读到的航线版本、点列/曲线控制点来自一致状态。</p>
     *
     * @return 航线当前状态；航线不存在返回 null（更新 0 行）
     */
    public RoutePo findRouteForUpdate(String routeId) {
        int locked = jdbc.update(
                "UPDATE route SET touch = touch + 1 WHERE route_id = ?", routeId);
        if (locked == 0) {
            return null;
        }
        Row row;
        try {
            row = jdbc.queryForObject(
                    "SELECT version, kind FROM route WHERE route_id = ?",
                    (rs, n) -> new Row(rs.getInt("version"),
                            RouteKind.valueOf(rs.getString("kind"))), routeId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
        if (row == null) {
            return null;
        }
        return loadGeometry(routeId, row);
    }

    private RoutePo loadGeometry(String routeId, Row row) {
        if (row.kind == RouteKind.QUADRATIC) {
            return new RoutePo(routeId, row.version, row.kind, List.of(), findSegments(routeId));
        }
        return new RoutePo(routeId, row.version, row.kind, findPoints(routeId), List.of());
    }

    private record Row(int version, RouteKind kind) {
    }

    private List<Point> findPoints(String routeId) {
        return jdbc.query(
                "SELECT x, y FROM route_point WHERE route_id = ? ORDER BY seq",
                (rs, n) -> new Point(rs.getInt("x"), rs.getInt("y")), routeId);
    }

    private List<CurveSegment> findSegments(String routeId) {
        return jdbc.query(
                "SELECT p0x, p0y, p1x, p1y, p2x, p2y FROM route_curve_segment "
                        + "WHERE route_id = ? ORDER BY seq",
                (rs, n) -> new CurveSegment(
                        new Point(rs.getInt("p0x"), rs.getInt("p0y")),
                        new Point(rs.getInt("p1x"), rs.getInt("p1y")),
                        new Point(rs.getInt("p2x"), rs.getInt("p2y"))),
                routeId);
    }

    /** 创建折线航线（初始版本 1）并写入点列（调用方负责事务）。 */
    public void insertRoute(String routeId, List<Point> points) {
        insertRouteRow(routeId, RouteKind.LINE);
        insertPoints(routeId, points);
    }

    /** 创建二次曲线航线（初始版本 1）并写入曲线段（调用方负责事务）。 */
    public void insertCurveRoute(String routeId, List<CurveSegment> segments) {
        insertRouteRow(routeId, RouteKind.QUADRATIC);
        insertSegments(routeId, segments);
    }

    private void insertRouteRow(String routeId, RouteKind kind) {
        jdbc.update("INSERT INTO route (route_id, version, kind, touch) VALUES (?, 1, ?, 0)",
                routeId, kind.name());
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

    /** 删除航线旧折线点列（调用方负责事务）。 */
    public void deletePoints(String routeId) {
        jdbc.update("DELETE FROM route_point WHERE route_id = ?", routeId);
    }

    /** 批量写入折线点列（调用方负责事务）。 */
    public void insertPoints(String routeId, List<Point> points) {
        int seq = 0;
        for (Point p : points) {
            jdbc.update("INSERT INTO route_point (route_id, seq, x, y) VALUES (?, ?, ?, ?)",
                    routeId, seq++, p.x(), p.y());
        }
    }

    /** 删除航线旧二次曲线段（调用方负责事务）。 */
    public void deleteSegments(String routeId) {
        jdbc.update("DELETE FROM route_curve_segment WHERE route_id = ?", routeId);
    }

    /** 批量写入二次曲线段（调用方负责事务，含完整冻结控制点 P0/P1/P2）。 */
    public void insertSegments(String routeId, List<CurveSegment> segments) {
        int seq = 0;
        for (CurveSegment seg : segments) {
            jdbc.update("INSERT INTO route_curve_segment "
                            + "(route_id, seq, p0x, p0y, p1x, p1y, p2x, p2y) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    routeId, seq++,
                    seg.p0().x(), seg.p0().y(), seg.p1().x(), seg.p1().y(),
                    seg.p2().x(), seg.p2().y());
        }
    }
}
