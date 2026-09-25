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
 * 处置任务及跨事件阻塞关系的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行的写事务内；创建任务额外持有 task_graph_lock 单行锁，
 * 使环检测与边写入相对其他创建串行化，保证并发反向依赖下最终图无环。
 */
@Repository
public class IncidentTaskRepository {

    /** 依赖图全局锁行的固定主键。 */
    private static final long GRAPH_LOCK_ID = 1L;

    private final JdbcTemplate jdbc;

    public IncidentTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IncidentTask> TASK_MAPPER = (rs, n) -> mapTask(rs);

    private static IncidentTask mapTask(ResultSet rs) throws SQLException {
        Timestamp doneAt = rs.getTimestamp("done_at");
        Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
        return new IncidentTask(
                rs.getLong("id"), rs.getLong("incident_id"), rs.getString("task_key"),
                rs.getString("group_code"), rs.getString("title"),
                TaskPriority.valueOf(rs.getString("priority")),
                TaskStatus.valueOf(rs.getString("status")),
                rs.getString("created_by"), rs.getString("done_by"),
                doneAt == null ? null : doneAt.toInstant(),
                rs.getString("cancelled_by"),
                cancelledAt == null ? null : cancelledAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 依赖图有向边：fromIncidentId（任务所属事件）→ toIncidentId（阻塞事件）。
     */
    public record Edge(long fromIncidentId, long toIncidentId) {
    }

    /**
     * 插入 OPEN 任务，返回生成主键。(incident_id, task_key) 唯一约束兜底并发重复插入。
     */
    public long insert(IncidentTask task) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_tasks (incident_id, task_key, group_code, title, priority, status,"
                            + " created_by, done_by, done_at, cancelled_by, cancelled_at,"
                            + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, task.incidentId());
            ps.setString(2, task.taskKey());
            ps.setString(3, task.groupCode());
            ps.setString(4, task.title());
            ps.setString(5, task.priority().name());
            ps.setString(6, task.status().name());
            ps.setString(7, task.createdBy());
            ps.setString(8, task.doneBy());
            ps.setTimestamp(9, task.doneAt() == null ? null : Timestamp.from(task.doneAt()));
            ps.setString(10, task.cancelledBy());
            ps.setTimestamp(11, task.cancelledAt() == null ? null : Timestamp.from(task.cancelledAt()));
            ps.setTimestamp(12, Timestamp.from(task.createdAt()));
            ps.setTimestamp(13, Timestamp.from(task.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按事件与 taskKey 查询任务，用于幂等比对与明细查询。
     */
    public Optional<IncidentTask> findByKey(long incidentId, String taskKey) {
        List<IncidentTask> rows = jdbc.query(
                "SELECT * FROM incident_tasks WHERE incident_id = ? AND task_key = ?",
                TASK_MAPPER, incidentId, taskKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部任务，按创建顺序返回。
     */
    public List<IncidentTask> listByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_tasks WHERE incident_id = ? ORDER BY id",
                TASK_MAPPER, incidentId);
    }

    /**
     * 查询事件仍 OPEN 的任务（解决门禁用），按创建顺序返回。
     */
    public List<IncidentTask> listOpenByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_tasks WHERE incident_id = ? AND status = 'OPEN'"
                + " ORDER BY id", TASK_MAPPER, incidentId);
    }

    /**
     * 统计事件任务数（每事件至多 20 个）。
     */
    public int countByIncident(long incidentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_tasks WHERE incident_id = ?", Integer.class, incidentId);
        return count == null ? 0 : count;
    }

    /**
     * 将 OPEN 任务置为 DONE，记录完成人与 UTC 时刻。
     */
    public void markDone(long id, String actor, Instant at) {
        jdbc.update("UPDATE incident_tasks SET status = 'DONE', done_by = ?, done_at = ?,"
                        + " updated_at = ? WHERE id = ?",
                actor, Timestamp.from(at), Timestamp.from(at), id);
    }

    /**
     * 将 OPEN 任务置为 CANCELLED，记录取消人与 UTC 时刻。
     */
    public void markCancelled(long id, String actor, Instant at) {
        jdbc.update("UPDATE incident_tasks SET status = 'CANCELLED', cancelled_by = ?,"
                        + " cancelled_at = ?, updated_at = ? WHERE id = ?",
                actor, Timestamp.from(at), Timestamp.from(at), id);
    }

    /**
     * 追加一条阻塞边（任务 → 阻塞事件）。(task_id, blocker_incident_id) 唯一。
     */
    public void insertBlocker(long taskId, long blockerIncidentId, Instant now) {
        jdbc.update("INSERT INTO incident_task_blockers (task_id, blocker_incident_id, created_at)"
                + " VALUES (?,?,?)", taskId, blockerIncidentId, Timestamp.from(now));
    }

    /**
     * 持有依赖图全局锁（单行 SELECT ... FOR UPDATE），串行化环检测与边写入。
     * 锁行不存在时先插入；并发首次插入由主键约束串行化。
     */
    public void lockGraph() {
        try {
            jdbc.update("INSERT INTO task_graph_lock (id) VALUES (?)", GRAPH_LOCK_ID);
        } catch (DuplicateKeyException e) {
            // 锁行已存在（含并发事务已提交），继续加锁
        }
        jdbc.queryForObject("SELECT id FROM task_graph_lock WHERE id = ? FOR UPDATE",
                Long.class, GRAPH_LOCK_ID);
    }

    /**
     * 环检测：在依赖图中判断从 fromIncidentId 出发沿阻塞边是否可达 toIncidentId。
     * 调用前必须已持有 lockGraph() 全局锁，保证检测与后续边写入串行一致。
     * 所有状态的任务边均参与构图（保守策略：已终态任务的依赖边仍阻止成环）。
     */
    public boolean isReachable(long fromIncidentId, long toIncidentId) {
        if (fromIncidentId == toIncidentId) {
            return true;
        }
        List<Edge> edges = jdbc.query(
                "SELECT t.incident_id, b.blocker_incident_id FROM incident_task_blockers b"
                        + " JOIN incident_tasks t ON t.id = b.task_id",
                (rs, n) -> new Edge(rs.getLong(1), rs.getLong(2)));
        Map<Long, List<Long>> adjacency = new HashMap<>();
        for (Edge edge : edges) {
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
}
