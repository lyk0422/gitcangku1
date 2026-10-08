package com.example.starter.repo;

import com.example.starter.domain.Point;
import com.example.starter.domain.QuadraticCurve;
import com.example.starter.domain.RouteKind;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 航线与航点/曲线控制点数据访问。
 */
@Repository
public class RouteRepository {

    private final JdbcTemplate jdbc;

    public RouteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按 routeId 查询航线（含当前版本几何），不存在返回 null。 */
    public RoutePo findRoute(String routeId) {
        try {
            return jdbc.queryForObject(
                    "SELECT version, kind FROM route WHERE route_id = ?",
                    (rs, n) -> loadRoute(routeId, rs.getInt("version"),
                            RouteKind.valueOf(rs.getString("kind"))),
                    routeId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 在当前事务内对航线行做真实更新（touch 加一）取得行级排他锁，并读取航线（含几何）。
     *
     * <p>更新为不同的值确保 H2/MySQL 不跳过加锁；与替换操作的行更新互斥，
     * 保证审核读到的航线版本与几何来自一致状态。</p>
     *
     * @return 航线当前状态；航线不存在返回 null（更新 0 行）
     */
    public RoutePo findRouteForUpdate(String routeId) {
        int locked = jdbc.update(
                "UPDATE route SET touch = touch + 1 WHERE route_id = ?", routeId);
        if (locked == 0) {
            return null;
        }
        try {
            var row = jdbc.queryForMap(
                    "SELECT version, kind FROM route WHERE route_id = ?", routeId);
            return loadRoute(routeId, ((Number) row.get("version")).intValue(),
                    RouteKind.valueOf((String) row.get("kind")));
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private RoutePo loadRoute(String routeId, int version, RouteKind kind) {
        if (kind == RouteKind.CURVE) {
            return new RoutePo(routeId, version, kind, List.of(), findCurves(routeId));
        }
        return new RoutePo(routeId, version, kind, findPoints(routeId), List.of());
    }

    private List<Point> findPoints(String routeId) {
        return jdbc.query(
                "SELECT x, y FROM route_point WHERE route_id = ? ORDER BY seq",
                (rs, n) -> new Point(rs.getInt("x"), rs.getInt("y")), routeId);
    }

    private List<QuadraticCurve> findCurves(String routeId) {
        // 每段固定 3 行（pt_seq 0/1/2），按段、点序号排序后顺序组装
        List<int[]> rows = jdbc.query(
                "SELECT seg_seq, pt_seq, x, y FROM route_curve_point "
                        + "WHERE route_id = ? ORDER BY seg_seq, pt_seq",
                (rs, n) -> new int[]{rs.getInt("seg_seq"), rs.getInt("pt_seq"),
                        rs.getInt("x"), rs.getInt("y")}, routeId);
        List<QuadraticCurve> segments = new ArrayList<>();
        Point[] trio = new Point[3];
        int currentSeg = -1;
        for (int[] row : rows) {
            if (row[0] != currentSeg) {
                currentSeg = row[0];
                trio = new Point[3];
            }
            trio[row[1]] = new Point(row[2], row[3]);
            if (trio[2] != null && trio[0] != null && trio[1] != null) {
                segments.add(new QuadraticCurve(trio[0], trio[1], trio[2]));
            }
        }
        return segments;
    }

    /** 创建直线航线（初始版本 1）并写入点列（调用方负责事务）。 */
    public void insertRoute(String routeId, List<Point> points) {
        jdbc.update("INSERT INTO route (route_id, version, kind, touch) VALUES (?, 1, 'LINE', 0)",
                routeId);
        insertPoints(routeId, points);
    }

    /** 创建二次曲线航线（初始版本 1）并写入控制点（调用方负责事务）。 */
    public void insertCurveRoute(String routeId, List<QuadraticCurve> segments) {
        jdbc.update("INSERT INTO route (route_id, version, kind, touch) VALUES (?, 1, 'CURVE', 0)",
                routeId);
        insertCurvePoints(routeId, segments);
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

    /** 删除航线旧点列/控制点（调用方负责事务）。 */
    public void deleteGeometry(String routeId) {
        jdbc.update("DELETE FROM route_point WHERE route_id = ?", routeId);
        jdbc.update("DELETE FROM route_curve_point WHERE route_id = ?", routeId);
    }

    /** 批量写入直线航线点列（调用方负责事务）。 */
    public void insertPoints(String routeId, List<Point> points) {
        int seq = 0;
        for (Point p : points) {
            jdbc.update("INSERT INTO route_point (route_id, seq, x, y) VALUES (?, ?, ?, ?)",
                    routeId, seq++, p.x(), p.y());
        }
    }

    /** 批量写入二次曲线控制点（调用方负责事务）。 */
    public void insertCurvePoints(String routeId, List<QuadraticCurve> segments) {
        for (int seg = 0; seg < segments.size(); seg++) {
            QuadraticCurve c = segments.get(seg);
            Point[] pts = {c.p0(), c.p1(), c.p2()};
            for (int pt = 0; pt < 3; pt++) {
                jdbc.update("INSERT INTO route_curve_point (route_id, seg_seq, pt_seq, x, y) "
                                + "VALUES (?, ?, ?, ?, ?)",
                        routeId, seg, pt, pts[pt].x(), pts[pt].y());
            }
        }
    }
}
