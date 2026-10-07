package com.example.starter.repo;

import com.example.starter.domain.CoverageInterval;
import com.example.starter.domain.Point;
import com.example.starter.domain.SegmentProfile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
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
        Integer version;
        try {
            version = jdbc.queryForObject(
                    "SELECT version FROM route WHERE route_id = ?", Integer.class, routeId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
        if (version == null) {
            return null;
        }
        List<Point> points = findPoints(routeId);
        return new RoutePo(routeId, version, points);
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
        Integer version = jdbc.queryForObject(
                "SELECT version FROM route WHERE route_id = ?", Integer.class, routeId);
        if (version == null) {
            return null;
        }
        List<Point> points = findPoints(routeId);
        return new RoutePo(routeId, version, points);
    }

    private List<Point> findPoints(String routeId) {
        return jdbc.query(
                "SELECT x, y FROM route_point WHERE route_id = ? ORDER BY seq",
                (rs, n) -> new Point(rs.getInt("x"), rs.getInt("y")), routeId);
    }

    /** 创建航线（初始版本 1）并写入点列（调用方负责事务）。 */
    public void insertRoute(String routeId, List<Point> points) {
        jdbc.update("INSERT INTO route (route_id, version, touch) VALUES (?, 1, 0)", routeId);
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

    // ============================ 航段通信档案 ============================

    /**
     * 查询航线当前版本各航段通信档案（按段序号升序）。
     *
     * @return 段档案列表；空列表表示旧无通信档案航线（不参与链路审查）
     */
    public List<SegmentProfile> findSegments(String routeId) {
        return jdbc.query(
                "SELECT seq, duration_ms, coverage FROM route_segment "
                        + "WHERE route_id = ? ORDER BY seq",
                (rs, n) -> new SegmentProfile(rs.getInt("duration_ms"),
                        decodeCoverage(rs.getString("coverage"))),
                routeId);
    }

    /** 批量写入航段通信档案（段序号从 0 开始，调用方负责事务）。 */
    public void insertSegments(String routeId, List<SegmentProfile> segments) {
        int seq = 0;
        for (SegmentProfile segment : segments) {
            jdbc.update("INSERT INTO route_segment (route_id, seq, duration_ms, coverage) "
                            + "VALUES (?, ?, ?, ?)",
                    routeId, seq++, segment.durationMs(), encodeCoverage(segment.coverage()));
        }
    }

    /** 删除航线旧航段通信档案（调用方负责事务）。 */
    public void deleteSegments(String routeId) {
        jdbc.update("DELETE FROM route_segment WHERE route_id = ?", routeId);
    }

    /** 覆盖区间列表编码："s-e,s-e"；空列表编码为空串（全段失联）。 */
    public static String encodeCoverage(List<CoverageInterval> coverage) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < coverage.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(coverage.get(i).start()).append('-').append(coverage.get(i).end());
        }
        return sb.toString();
    }

    /** 覆盖区间列表解码，空串返回空列表。 */
    public static List<CoverageInterval> decodeCoverage(String text) {
        List<CoverageInterval> coverage = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            for (String pair : text.split(",")) {
                String[] se = pair.split("-");
                coverage.add(new CoverageInterval(
                        Integer.parseInt(se[0]), Integer.parseInt(se[1])));
            }
        }
        return coverage;
    }
}
