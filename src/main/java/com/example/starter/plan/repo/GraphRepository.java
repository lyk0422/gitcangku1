package com.example.starter.plan.repo;

import com.example.starter.plan.model.GraphSegment;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 线路图持久化：单行元信息（当前版本）与当前版本的有向区段清单。
 * 仅保留最新一版区段，图替换整体覆盖；历史版本由寻径发布快照冻结，无需保留。
 */
@Repository
public class GraphRepository {

    private final JdbcTemplate jdbc;

    public GraphRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 当前线路图版本；尚未建图时返回空（视为版本 0）。
     */
    public Optional<Integer> findVersion() {
        return jdbc.query("SELECT version FROM rail_graph_meta WHERE id = 1",
                (rs, n) -> rs.getInt("version")).stream().findFirst();
    }

    /**
     * 当前版本的有向区段清单，按提交序号升序。
     */
    public List<GraphSegment> findSegments() {
        return jdbc.query("SELECT segment_id, from_station, to_station, minutes"
                        + " FROM rail_graph_segment ORDER BY seq",
                (rs, n) -> new GraphSegment(rs.getString("segment_id"),
                        rs.getString("from_station"), rs.getString("to_station"),
                        rs.getInt("minutes")));
    }

    /**
     * 整体替换线路图：元信息版本推进到 newVersion，区段清单全量覆盖。须在事务与发布锁内调用。
     */
    public void replaceGraph(int newVersion, List<GraphSegment> segments, long nowMillis) {
        int updated = jdbc.update("UPDATE rail_graph_meta SET version = ?, updated_at = ? WHERE id = 1",
                newVersion, nowMillis);
        if (updated == 0) {
            jdbc.update("INSERT INTO rail_graph_meta (id, version, updated_at) VALUES (1, ?, ?)",
                    newVersion, nowMillis);
        }
        jdbc.update("DELETE FROM rail_graph_segment");
        List<Object[]> rows = new java.util.ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            GraphSegment s = segments.get(i);
            rows.add(new Object[]{newVersion, i, s.segmentId(), s.fromStation(), s.toStation(),
                    s.minutes()});
        }
        jdbc.batchUpdate(
                "INSERT INTO rail_graph_segment"
                        + " (graph_version, seq, segment_id, from_station, to_station, minutes)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                rows);
    }
}
