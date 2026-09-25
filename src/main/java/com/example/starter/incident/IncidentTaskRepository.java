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
        Long blockedZoneId = rs.getObject("blocked_zone_id", Long.class);
        return new IncidentTask(
                rs.getLong("id"), rs.getLong("incident_id"), rs.getString("task_key"),
                rs.getString("group_code"), rs.getString("title"),
                TaskStatus.valueOf(rs.getString("status")),
                rs.getString("work_grid"),
                rs.getString("created_by"), rs.getString("done_by"),
                doneAt == null ? null : doneAt.toInstant(),
                rs.getString("cancelled_by"),
                cancelledAt == null ? null : cancelledAt.toInstant(),
                blockedZoneId, rs.getString("blocked_snapshot"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 依赖图有向边：fromIncidentId（任务所属事件）→ toIncidentId（阻塞事件）。
     */
    public record Edge(long fromIncidentId, long toIncidentId) {
    }

    /**
     * 插入任务（初始状态由调用方给定），返回生成主键。(incident_id, task_key) 唯一约束兜底并发重复插入。
     */
    public long insert(IncidentTask task) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_tasks (incident_id, task_key, group_code, title, status,"
                            + " work_grid, created_by, done_by, done_at, cancelled_by, cancelled_at,"
                            + " blocked_zone_id, blocked_snapshot, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, task.incidentId());
            ps.setString(2, task.taskKey());
            ps.setString(3, task.groupCode());
            ps.setString(4, task.title());
            ps.setString(5, task.status().name());
            ps.setString(6, task.workGrid());
            ps.setString(7, task.createdBy());
            ps.setString(8, task.doneBy());
            ps.setTimestamp(9, task.doneAt() == null ? null : Timestamp.from(task.doneAt()));
            ps.setString(10, task.cancelledBy());
            ps.setTimestamp(11, task.cancelledAt() == null ? null : Timestamp.from(task.cancelledAt()));
            if (task.blockedZoneId() == null) {
                ps.setNull(12, java.sql.Types.BIGINT);
            } else {
                ps.setLong(12, task.blockedZoneId());
            }
            ps.setString(13, task.blockedSnapshot());
            ps.setTimestamp(14, Timestamp.from(task.createdAt()));
            ps.setTimestamp(15, Timestamp.from(task.updatedAt()));
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
     * 按主键查询任务。
     */
    public Optional<IncidentTask> findById(long id) {
        List<IncidentTask> rows = jdbc.query("SELECT * FROM incident_tasks WHERE id = ?",
                TASK_MAPPER, id);
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
     * 查询事件仍 OPEN 的任务（兼容原解决门禁调用），按创建顺序返回。
     */
    public List<IncidentTask> listOpenByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_tasks WHERE incident_id = ? AND status = 'OPEN'"
                + " ORDER BY id", TASK_MAPPER, incidentId);
    }

    /**
     * 查询事件所有未进入终态（DONE/CANCELLED/EVACUATED）的任务：
     * 解决事件要求处置任务全部终结，撤离终态同样不可再处置。
     */
    public List<IncidentTask> listUnfinishedByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_tasks WHERE incident_id = ?"
                + " AND status NOT IN ('DONE','CANCELLED','EVACUATED') ORDER BY id",
                TASK_MAPPER, incidentId);
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
     * 将任务置为 DONE（带前置状态条件，避免并发重复流转），返回受影响行数。
     */
    public int markDoneIfIn(long id, String actor, Instant at, List<TaskStatus> expected) {
        return conditionalUpdate(id, expected,
                "status = 'DONE', done_by = ?, done_at = ?, updated_at = ?", ps -> {
                    ps.setString(1, actor);
                    ps.setTimestamp(2, Timestamp.from(at));
                    ps.setTimestamp(3, Timestamp.from(at));
                });
    }

    /**
     * 将任务置为 CANCELLED（带前置状态条件），返回受影响行数。
     */
    public int markCancelledIfIn(long id, String actor, Instant at, List<TaskStatus> expected) {
        return conditionalUpdate(id, expected,
                "status = 'CANCELLED', cancelled_by = ?, cancelled_at = ?, updated_at = ?", ps -> {
                    ps.setString(1, actor);
                    ps.setTimestamp(2, Timestamp.from(at));
                    ps.setTimestamp(3, Timestamp.from(at));
                });
    }

    /**
     * 批量将任务置为 DISPATCHED（带前置状态条件），返回受影响行数。
     */
    public int markDispatchedIfIn(List<Long> ids, Instant at, List<TaskStatus> expected) {
        if (ids.isEmpty()) {
            return 0;
        }
        String inIds = String.join(",", ids.stream().map(x -> "?").toList());
        String inStates = String.join(",", expected.stream().map(x -> "?").toList());
        Object[] params = new Object[1 + ids.size() + expected.size()];
        params[0] = Timestamp.from(at);
        int p = 1;
        for (Long id : ids) {
            params[p++] = id;
        }
        for (TaskStatus s : expected) {
            params[p++] = s.name();
        }
        return jdbc.update("UPDATE incident_tasks SET status = 'DISPATCHED', updated_at = ? WHERE id IN ("
                        + inIds + ") AND status IN (" + inStates + ")",
                params);
    }

    /**
     * 将任务置为 IN_PROGRESS（带前置状态条件），返回受影响行数。
     */
    public int markInProgressIfIn(long id, Instant at, List<TaskStatus> expected) {
        return conditionalUpdate(id, expected, "status = 'IN_PROGRESS', updated_at = ?",
                ps -> ps.setTimestamp(1, Timestamp.from(at)));
    }

    /**
     * 将未开始任务固化为 EVACUATION_BLOCKED 并写入区域快照，返回受影响行数。
     */
    public int markBlockedIfNotStarted(long id, long zoneId, String snapshotJson, Instant at) {
        return jdbc.update("UPDATE incident_tasks SET status = 'EVACUATION_BLOCKED', blocked_zone_id = ?,"
                        + " blocked_snapshot = ?, updated_at = ? WHERE id = ? AND status IN"
                        + " ('OPEN','DISPATCHED')",
                zoneId, snapshotJson, Timestamp.from(at), id);
    }

    /**
     * 查询某区域阻断的全部任务（区域结束恢复用）。
     */
    public List<IncidentTask> listBlockedByZone(long zoneId) {
        return jdbc.query(
                "SELECT * FROM incident_tasks WHERE blocked_zone_id = ? AND status = 'EVACUATION_BLOCKED'"
                        + " ORDER BY id", TASK_MAPPER, zoneId);
    }

    /**
     * 将阻断任务恢复为 OPEN 并清空固化快照，返回受影响行数。
     */
    public int reopenBlocked(long id, Instant at) {
        return jdbc.update("UPDATE incident_tasks SET status = 'OPEN', blocked_zone_id = NULL,"
                        + " blocked_snapshot = NULL, updated_at = ? WHERE id = ?"
                        + " AND status = 'EVACUATION_BLOCKED'",
                Timestamp.from(at), id);
    }

    /**
     * 到期区域结束但任务仍被另一有效区域命中：改挂阻断区域与快照，保持 EVACUATION_BLOCKED。
     */
    public int repointBlocked(long id, long newZoneId, String snapshotJson, Instant at) {
        return jdbc.update("UPDATE incident_tasks SET blocked_zone_id = ?, blocked_snapshot = ?,"
                        + " updated_at = ? WHERE id = ? AND status = 'EVACUATION_BLOCKED'",
                newZoneId, snapshotJson, Timestamp.from(at), id);
    }

    /**
     * 将进行中任务登记为 EVACUATED 终态，返回受影响行数。
     */
    public int markEvacuatedIfInProgress(long id, Instant at) {
        return jdbc.update("UPDATE incident_tasks SET status = 'EVACUATED', updated_at = ? WHERE id = ?"
                        + " AND status = 'IN_PROGRESS'",
                Timestamp.from(at), id);
    }

    @FunctionalInterface
    private interface PreparedBinder {
        void bind(java.sql.PreparedStatement ps) throws SQLException;
    }

    private int conditionalUpdate(long id, List<TaskStatus> expected, String setClause,
                                  PreparedBinder binder) {
        String inStates = String.join(",", expected.stream().map(x -> "?").toList());
        return jdbc.update(con -> {
            var ps = con.prepareStatement("UPDATE incident_tasks SET " + setClause
                    + " WHERE id = ? AND status IN (" + inStates + ")");
            binder.bind(ps);
            int base = countPlaceholders(setClause);
            ps.setLong(base + 1, id);
            for (int i = 0; i < expected.size(); i++) {
                ps.setString(base + 2 + i, expected.get(i).name());
            }
            return ps;
        });
    }

    private static int countPlaceholders(String setClause) {
        int count = 0;
        for (int i = 0; i < setClause.length(); i++) {
            if (setClause.charAt(i) == '?') {
                count++;
            }
        }
        return count;
    }

    /**
     * 追加一条阻塞边（任务 → 阻塞事件）。(task_id, blocker_incident_id) 唯一。
     */
    public void insertBlocker(long taskId, long blockerIncidentId, Instant now) {
        jdbc.update("INSERT INTO incident_task_blockers (task_id, blocker_incident_id, created_at)"
                + " VALUES (?,?,?)", taskId, blockerIncidentId, Timestamp.from(now));
    }

    private static final RowMapper<DispatchLease> LEASE_MAPPER = (rs, n) -> new DispatchLease(
            rs.getLong("id"), rs.getLong("task_id"), rs.getString("dispatched_by"),
            rs.getString("command_key"), rs.getTimestamp("dispatched_at").toInstant(),
            rs.getTimestamp("consumed_at") == null ? null
                    : rs.getTimestamp("consumed_at").toInstant());

    /**
     * 插入派工租约，与任务置 DISPATCHED 同事务。每任务至多一条（唯一约束兜底）。
     */
    public void insertLease(long taskId, String dispatchedBy, String commandKey, Instant at) {
        jdbc.update("INSERT INTO task_dispatch_leases (task_id, dispatched_by, command_key,"
                + " dispatched_at, consumed_at) VALUES (?,?,?,?,NULL)",
                taskId, dispatchedBy, commandKey, Timestamp.from(at));
    }

    /**
     * 查询任务的派工租约。
     */
    public Optional<DispatchLease> findLeaseByTask(long taskId) {
        List<DispatchLease> rows = jdbc.query(
                "SELECT * FROM task_dispatch_leases WHERE task_id = ?", LEASE_MAPPER, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 消费派工租约（任务开始时写入消费时刻），返回受影响行数；0 表示无未消费租约。
     */
    public int consumeLease(long taskId, Instant at) {
        return jdbc.update("UPDATE task_dispatch_leases SET consumed_at = ? WHERE task_id = ?"
                + " AND consumed_at IS NULL", Timestamp.from(at), taskId);
    }

    /**
     * 删除任务的派工租约（已派工任务被疏散阻断时随状态一起回退为“未派工”，
     * 使区域结束恢复 OPEN 后可重新派工）。
     */
    public int deleteLease(long taskId) {
        return jdbc.update("DELETE FROM task_dispatch_leases WHERE task_id = ?", taskId);
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
