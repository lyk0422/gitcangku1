package com.example.starter.incident;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 跨事件依赖图仓储：当前边集（graph_edges）与图版本（graph_version 单行）。
 * 边集由任务阻塞创建与提案激活共同维护；每次边集实际变更版本号单调递增 1。
 * 所有写路径均处于持有 task_graph_lock 全局锁的写事务内，读写按事务提交顺序收敛。
 */
@Repository
public class DependencyGraphRepository {

    /** 图版本单行的固定主键。 */
    private static final long VERSION_ROW_ID = 1L;

    private final JdbcTemplate jdbc;

    public DependencyGraphRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 依赖图有向边：fromIncidentId（依赖方，被阻塞）→ toIncidentId（被依赖方，阻塞方）。
     */
    public record GraphEdge(long fromIncidentId, long toIncidentId) {
    }

    private static final RowMapper<GraphEdge> EDGE_MAPPER =
            (rs, n) -> new GraphEdge(rs.getLong(1), rs.getLong(2));

    /**
     * 查询当前完整边集（按事件 id 排序，稳定顺序）。
     */
    public List<GraphEdge> listEdges() {
        return jdbc.query("SELECT from_incident_id, to_incident_id FROM graph_edges"
                + " ORDER BY from_incident_id, to_incident_id", EDGE_MAPPER);
    }

    /**
     * 插入一条边（已存在则忽略）。
     *
     * @return true 表示实际新增（边集发生变化）
     */
    public boolean insertEdgeIfAbsent(long fromIncidentId, long toIncidentId, Instant now) {
        int rows = jdbc.update("INSERT INTO graph_edges (from_incident_id, to_incident_id, created_at)"
                        + " SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM graph_edges"
                        + " WHERE from_incident_id = ? AND to_incident_id = ?)",
                fromIncidentId, toIncidentId, Timestamp.from(now), fromIncidentId, toIncidentId);
        return rows > 0;
    }

    /**
     * 删除一条边，返回删除行数（0 表示边不存在）。
     */
    public int deleteEdge(long fromIncidentId, long toIncidentId) {
        return jdbc.update("DELETE FROM graph_edges WHERE from_incident_id = ? AND to_incident_id = ?",
                fromIncidentId, toIncidentId);
    }

    /**
     * 读取当前图版本；版本行尚未初始化时返回 0。
     */
    public long currentVersion() {
        List<Long> rows = jdbc.query("SELECT version FROM graph_version WHERE id = ?",
                (rs, n) -> rs.getLong(1), VERSION_ROW_ID);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    /**
     * 确保版本行存在（初始版本 0）；并发首次插入由主键约束串行化。
     */
    public void ensureVersionRow(Instant now) {
        try {
            jdbc.update("INSERT INTO graph_version (id, version, updated_at) VALUES (?, 0, ?)",
                    VERSION_ROW_ID, Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            // 版本行已存在（含并发事务已提交），无需处理
        }
    }

    /**
     * 图版本加 1。调用前必须已持有 task_graph_lock 全局锁，保证与边集写入原子提交。
     */
    public void bumpVersion(Instant now) {
        jdbc.update("UPDATE graph_version SET version = version + 1, updated_at = ? WHERE id = ?",
                Timestamp.from(now), VERSION_ROW_ID);
    }

    /**
     * 环检测：在给定边集上判断从 fromIncidentId 出发沿边是否可达 toIncidentId。
     */
    public static boolean isReachable(List<GraphEdge> edges, long fromIncidentId, long toIncidentId) {
        if (fromIncidentId == toIncidentId) {
            return true;
        }
        Map<Long, List<Long>> adjacency = new HashMap<>();
        for (GraphEdge edge : edges) {
            adjacency.computeIfAbsent(edge.fromIncidentId(), k -> new ArrayList<>())
                    .add(edge.toIncidentId());
        }
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(fromIncidentId);
        visited.add(fromIncidentId);
        while (!queue.isEmpty()) {
            long current = queue.poll();
            for (Long next : adjacency.getOrDefault(current, List.of())) {
                if (next == toIncidentId) {
                    return true;
                }
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return false;
    }

    /**
     * 判断给定边集是否存在环（Kahn 拓扑排序：无法全部出队即成环）。
     */
    public static boolean hasCycle(List<GraphEdge> edges) {
        Map<Long, List<Long>> adjacency = new HashMap<>();
        Map<Long, Integer> indegree = new HashMap<>();
        for (GraphEdge edge : edges) {
            adjacency.computeIfAbsent(edge.fromIncidentId(), k -> new ArrayList<>())
                    .add(edge.toIncidentId());
            indegree.merge(edge.toIncidentId(), 1, Integer::sum);
            indegree.putIfAbsent(edge.fromIncidentId(), 0);
        }
        Deque<Long> queue = new ArrayDeque<>();
        for (Map.Entry<Long, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }
        int processed = 0;
        while (!queue.isEmpty()) {
            long current = queue.poll();
            processed++;
            for (Long next : adjacency.getOrDefault(current, List.of())) {
                int remaining = indegree.merge(next, -1, Integer::sum);
                if (remaining == 0) {
                    queue.add(next);
                }
            }
        }
        return processed < indegree.size();
    }
}
