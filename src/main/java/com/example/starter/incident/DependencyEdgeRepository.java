package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 依赖图权威边与图版本元数据的 JDBC 仓储。
 * 权威边表 incident_dependency_edges 同时承载任务创建（TASK）与提案激活（PROPOSAL）
 * 引入的边，是环检测与后态校验的唯一构图来源；incident_task_blockers 保留每个任务
 * 的强制声明，用于完成门禁与删除保护。
 * 图写入方（创建任务、激活提案）必须先持有 task_graph_lock 全局锁并锁定版本元数据行。
 */
@Repository
public class DependencyEdgeRepository {

    /** 图版本元数据固定单行主键；初始版本为 1。 */
    private static final long META_ID = 1L;
    private static final long INITIAL_VERSION = 1L;

    private final JdbcTemplate jdbc;

    public DependencyEdgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DependencyEdge> EDGE_MAPPER = (rs, n) -> new DependencyEdge(
            rs.getLong("id"), rs.getLong("from_incident_id"), rs.getLong("to_incident_id"),
            rs.getString("source"), (Long) rs.getObject("ref_id"),
            rs.getTimestamp("created_at").toInstant());

    /**
     * 全量边视图（含两端事件业务键与来源），供快照与校验使用。
     */
    public record EdgeRow(long fromIncidentId, long toIncidentId, String fromIncidentKey,
                          String toIncidentKey, String source, Long refId) {
    }

    private static final RowMapper<EdgeRow> EDGE_ROW_MAPPER = (rs, n) -> new EdgeRow(
            rs.getLong("from_incident_id"), rs.getLong("to_incident_id"),
            rs.getString("from_incident_key"), rs.getString("to_incident_key"),
            rs.getString("source"), (Long) rs.getObject("ref_id"));

    /**
     * 确保图版本元数据单行存在（不存在则初始化为版本 1）。并发首次插入由主键约束串行化。
     */
    public void ensureMeta(Instant now) {
        try {
            jdbc.update("INSERT INTO dependency_graph_meta (id, graph_version, updated_at)"
                    + " VALUES (?,?,?)", META_ID, INITIAL_VERSION, Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            // 元数据行已存在，沿用当前版本
        }
    }

    /**
     * 读取当前图版本（不加锁，只读场景）；元数据行尚未初始化时视为初始版本 1。
     */
    public long currentVersion() {
        List<Long> rows = jdbc.query(
                "SELECT graph_version FROM dependency_graph_meta WHERE id = ?",
                (rs, n) -> rs.getLong(1), META_ID);
        return rows.stream().findFirst().orElse(INITIAL_VERSION);
    }

    /**
     * 锁定版本元数据行并返回当前版本（SELECT ... FOR UPDATE）。
     * 调用前必须已持有 task_graph_lock 全局锁。
     */
    public long lockMetaForVersion() {
        List<Long> rows = jdbc.query(
                "SELECT graph_version FROM dependency_graph_meta WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getLong(1), META_ID);
        return rows.stream().findFirst().orElseThrow(
                () -> new IllegalStateException("依赖图版本元数据不存在"));
    }

    /** 将图版本原子推进到 newVersion。 */
    public void bumpVersion(long newVersion, Instant now) {
        int updated = jdbc.update(
                "UPDATE dependency_graph_meta SET graph_version = ?, updated_at = ? WHERE id = ?",
                newVersion, Timestamp.from(now), META_ID);
        if (updated != 1) {
            throw new IllegalStateException("图版本推进失败");
        }
    }

    /**
     * 读取全量权威依赖边（含两端事件键）。构图唯一来源为 incident_dependency_edges。
     */
    public List<EdgeRow> listAllEdges() {
        return jdbc.query(
                "SELECT e.from_incident_id, e.to_incident_id, fi.incident_key AS from_incident_key,"
                        + " ti.incident_key AS to_incident_key, e.source, e.ref_id"
                        + " FROM incident_dependency_edges e"
                        + " JOIN incidents fi ON fi.id = e.from_incident_id"
                        + " JOIN incidents ti ON ti.id = e.to_incident_id",
                EDGE_ROW_MAPPER);
    }

    /**
     * 新增权威边（(from,to) 结构化唯一，已存在则不重复插入）。
     * 多来源声明同一条边时只保留首次写入的来源行，图上边不重复。
     *
     * @return 是否实际插入新行
     */
    public boolean insertEdgeIfAbsent(long fromIncidentId, long toIncidentId, String source,
                                      Long refId, Instant now) {
        try {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con -> {
                var ps = con.prepareStatement(
                        "INSERT INTO incident_dependency_edges (from_incident_id, to_incident_id,"
                                + " source, ref_id, created_at) VALUES (?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, fromIncidentId);
                ps.setLong(2, toIncidentId);
                ps.setString(3, source);
                ps.setObject(4, refId);
                ps.setTimestamp(5, Timestamp.from(now));
                return ps;
            }, keys);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 物理删除一条有向边，返回是否实际删除。提案删除以权威边表为准。
     */
    public boolean deleteEdge(long fromIncidentId, long toIncidentId) {
        int updated = jdbc.update(
                "DELETE FROM incident_dependency_edges WHERE from_incident_id = ? AND to_incident_id = ?",
                fromIncidentId, toIncidentId);
        return updated > 0;
    }

    /**
     * 是否存在进行中（OPEN）任务仍声明 from→to 为强制阻塞边。
     * 删除保护：存在即不允许提案删除该边。
     */
    public boolean openTaskDeclaresEdge(long fromIncidentId, long toIncidentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_blockers b"
                        + " JOIN incident_tasks t ON t.id = b.task_id"
                        + " WHERE t.incident_id = ? AND b.blocker_incident_id = ?"
                        + " AND t.status = 'OPEN'",
                Integer.class, fromIncidentId, toIncidentId);
        return count != null && count > 0;
    }

    /**
     * 删除已终态（DONE/CANCELLED）任务对 from→to 的强制阻塞声明。
     * 终态任务不再受完成门禁约束，提案删除边时同步清理这些残留声明，
     * 使阻塞声明表与权威边表保持一致。返回删除行数。
     */
    public int deleteTerminalTaskDeclarations(long fromIncidentId, long toIncidentId) {
        return jdbc.update(
                "DELETE FROM incident_task_blockers WHERE blocker_incident_id = ?"
                        + " AND task_id IN (SELECT id FROM incident_tasks"
                        + " WHERE incident_id = ? AND status <> 'OPEN')",
                toIncidentId, fromIncidentId);
    }

    /**
     * from 事件下是否存在已完成（DONE）任务。新增前置依赖时用于后态保护。
     */
    public boolean existsDoneTask(long fromIncidentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_tasks WHERE incident_id = ? AND status = 'DONE'",
                Integer.class, fromIncidentId);
        return count != null && count > 0;
    }

    /**
     * 环检测：在当前权威依赖图中判断从 fromIncidentId 出发沿边是否可达 toIncidentId。
     * 调用前必须已持有 task_graph_lock 全局锁，保证检测与后续写入串行一致。
     */
    public boolean isReachable(long fromIncidentId, long toIncidentId) {
        if (fromIncidentId == toIncidentId) {
            return true;
        }
        List<long[]> edges = jdbc.query(
                "SELECT from_incident_id, to_incident_id FROM incident_dependency_edges",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)});
        Map<Long, List<Long>> adjacency = new HashMap<>();
        for (long[] edge : edges) {
            adjacency.computeIfAbsent(edge[0], k -> new ArrayList<>()).add(edge[1]);
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
}
