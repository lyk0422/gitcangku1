package com.example.starter.repo;

import com.example.starter.domain.CoverageInterval;
import com.example.starter.domain.Point;
import com.example.starter.domain.SegmentLink;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 航线、航点与航段通信档案数据访问。
 */
@Repository
public class RouteRepository {

    private final JdbcTemplate jdbc;

    public RouteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按 routeId 查询航线（含当前版本点列与通信档案），不存在返回 null。 */
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
        return new RoutePo(routeId, version, findPoints(routeId), findSegments(routeId));
    }

    /**
     * 在当前事务内对航线行做真实更新（touch 加一）取得行级排他锁，并读取航线（含点列与通信档案）。
     *
     * <p>更新为不同的值确保 H2/MySQL 不跳过加锁；与替换操作的行更新互斥，
     * 保证审核读到的航线版本、点列与通信档案来自一致状态。</p>
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
        return new RoutePo(routeId, version, findPoints(routeId), findSegments(routeId));
    }

    private List<Point> findPoints(String routeId) {
        return jdbc.query(
                "SELECT x, y FROM route_point WHERE route_id = ? ORDER BY seq",
                (rs, n) -> new Point(rs.getInt("x"), rs.getInt("y")), routeId);
    }

    /** 读取当前版本通信档案；无档案（旧式航线）返回 null。 */
    private List<SegmentLink> findSegments(String routeId) {
        List<Long> durations = jdbc.query(
                "SELECT seq, duration_ms FROM route_segment WHERE route_id = ? ORDER BY seq",
                (rs, n) -> rs.getLong("duration_ms"), routeId);
        if (durations.isEmpty()) {
            return null;
        }
        List<SegmentLink> segments = new ArrayList<>(durations.size());
        for (int seq = 0; seq < durations.size(); seq++) {
            int segmentSeq = seq;
            List<CoverageInterval> coverage = jdbc.query(
                    "SELECT from_tick, to_tick FROM route_segment_coverage "
                            + "WHERE route_id = ? AND seq = ? ORDER BY cov_idx",
                    (rs, n) -> new CoverageInterval(
                            rs.getInt("from_tick"), rs.getInt("to_tick")),
                    routeId, segmentSeq);
            segments.add(new SegmentLink(durations.get(seq), coverage));
        }
        return segments;
    }

    /** 创建航线（初始版本 1）并写入点列与通信档案（调用方负责事务）。 */
    public void insertRoute(String routeId, List<Point> points, List<SegmentLink> segments) {
        jdbc.update("INSERT INTO route (route_id, version, touch) VALUES (?, 1, 0)", routeId);
        insertPoints(routeId, points);
        if (segments != null) {
            insertSegments(routeId, segments);
        }
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

    /** 删除航线旧通信档案（调用方负责事务）。 */
    public void deleteSegments(String routeId) {
        jdbc.update("DELETE FROM route_segment_coverage WHERE route_id = ?", routeId);
        jdbc.update("DELETE FROM route_segment WHERE route_id = ?", routeId);
    }

    /** 批量写入航线点列（调用方负责事务）。 */
    public void insertPoints(String routeId, List<Point> points) {
        int seq = 0;
        for (Point p : points) {
            jdbc.update("INSERT INTO route_point (route_id, seq, x, y) VALUES (?, ?, ?, ?)",
                    routeId, seq++, p.x(), p.y());
        }
    }

    /** 批量写入航段通信档案与覆盖区间（调用方负责事务）。 */
    public void insertSegments(String routeId, List<SegmentLink> segments) {
        int seq = 0;
        for (SegmentLink segment : segments) {
            jdbc.update("INSERT INTO route_segment (route_id, seq, duration_ms) VALUES (?, ?, ?)",
                    routeId, seq, segment.durationMs());
            int covIdx = 0;
            for (CoverageInterval interval : segment.coverage()) {
                jdbc.update("INSERT INTO route_segment_coverage "
                                + "(route_id, seq, cov_idx, from_tick, to_tick) VALUES (?, ?, ?, ?, ?)",
                        routeId, seq, covIdx++, interval.from(), interval.to());
            }
            seq++;
        }
    }
}
