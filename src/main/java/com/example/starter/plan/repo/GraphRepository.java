package com.example.starter.plan.repo;

import com.example.starter.plan.model.LineSegment;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 线路图版本与区段的 JDBC 持久化。图按版本整体替换，历史版本区段保留。
 */
@Repository
public class GraphRepository {

    private static final RowMapper<LineSegment> SEGMENT_MAPPER = (rs, n) -> new LineSegment(
            rs.getString("section_id"),
            rs.getString("from_station"),
            rs.getString("to_station"),
            rs.getInt("travel_minutes"));

    private final JdbcTemplate jdbc;

    public GraphRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取当前图版本（不加锁）；初始为 0 表示尚未发布任何图。
     */
    public int currentVersion() {
        return jdbc.queryForObject("SELECT version FROM rail_line_graph WHERE id = 1", Integer.class);
    }

    /**
     * 读取当前图版本并加行级写锁，须在事务内调用，串行化图替换。
     */
    public int currentVersionForUpdate() {
        return jdbc.queryForObject("SELECT version FROM rail_line_graph WHERE id = 1 FOR UPDATE",
                Integer.class);
    }

    /**
     * 将当前图版本推进到指定版本。
     */
    public void updateVersion(int version, long nowMillis) {
        jdbc.update("UPDATE rail_line_graph SET version = ?, updated_at = ? WHERE id = 1",
                version, nowMillis);
    }

    /**
     * 逐条写入指定图版本的区段清单，保持提交顺序（区段数量上限 20，逐条即可）。
     */
    public void insertSegments(int graphVersion, List<LineSegment> segments) {
        for (int i = 0; i < segments.size(); i++) {
            LineSegment s = segments.get(i);
            jdbc.update("INSERT INTO rail_line_segment"
                            + " (graph_version, seq, section_id, from_station, to_station, travel_minutes)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    graphVersion, i, s.sectionId(), s.fromStation(), s.toStation(),
                    s.travelMinutes());
        }
    }

    /**
     * 读取指定图版本的区段清单，按提交顺序。
     */
    public List<LineSegment> findSegments(int graphVersion) {
        return jdbc.query("SELECT section_id, from_station, to_station, travel_minutes"
                        + " FROM rail_line_segment WHERE graph_version = ? ORDER BY seq",
                SEGMENT_MAPPER, graphVersion);
    }
}
