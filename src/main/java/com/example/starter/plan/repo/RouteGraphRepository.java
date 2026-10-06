package com.example.starter.plan.repo;

import com.example.starter.plan.model.RouteSegment;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 线路图版本与区段的 JDBC 持久化。版本从 1 起，替换成功一次加一，历史版本保留不改写。
 */
@Repository
public class RouteGraphRepository {

    private static final RowMapper<RouteSegment> SEGMENT_MAPPER = (rs, n) -> new RouteSegment(
            rs.getString("segment_id"),
            rs.getString("from_station"),
            rs.getString("to_station"),
            rs.getInt("minutes"));

    private final JdbcTemplate jdbc;

    public RouteGraphRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 当前线路图版本；尚无图时返回 0。须在发布锁内读取以保证与替换操作一致。
     */
    public int currentVersion() {
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM rail_route_graph", Integer.class);
        return version == null ? 0 : version;
    }

    /**
     * 查询指定版本的全部站点，按站点名升序。
     */
    public List<String> findStations(int graphVersion) {
        return jdbc.query("SELECT station FROM rail_route_station"
                        + " WHERE graph_version = ? ORDER BY station",
                (rs, n) -> rs.getString("station"), graphVersion);
    }

    /**
     * 查询指定版本的全部区段，按区段编号升序（保证寻径枚举顺序确定）。
     */
    public List<RouteSegment> findSegments(int graphVersion) {
        return jdbc.query("SELECT segment_id, from_station, to_station, minutes"
                        + " FROM rail_route_segment WHERE graph_version = ? ORDER BY segment_id",
                SEGMENT_MAPPER, graphVersion);
    }

    /**
     * 追加新版本线路图（版本行、站点与全部区段同事务写入）。
     */
    public void insertGraph(int graphVersion, List<String> stations, List<RouteSegment> segments,
                            long nowMillis) {
        jdbc.update("INSERT INTO rail_route_graph (version, created_at) VALUES (?, ?)",
                graphVersion, nowMillis);
        jdbc.batchUpdate(
                "INSERT INTO rail_route_station (graph_version, station) VALUES (?, ?)",
                stations, stations.size(),
                (ps, station) -> {
                    ps.setInt(1, graphVersion);
                    ps.setString(2, station);
                });
        jdbc.batchUpdate(
                "INSERT INTO rail_route_segment"
                        + " (graph_version, segment_id, from_station, to_station, minutes)"
                        + " VALUES (?, ?, ?, ?, ?)",
                segments, segments.size(),
                (ps, s) -> {
                    ps.setInt(1, graphVersion);
                    ps.setString(2, s.segmentId());
                    ps.setString(3, s.fromStation());
                    ps.setString(4, s.toStation());
                    ps.setInt(5, s.minutes());
                });
    }
}
