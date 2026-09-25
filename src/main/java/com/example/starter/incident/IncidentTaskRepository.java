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
 * 状态推进（开始/完成/取消/风险进出）均使用带状态条件的 UPDATE，
 * 与资质撤销的条件更新按行锁提交顺序裁决，未完成态才会被改写。
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
        Timestamp plannedCompleteAt = rs.getTimestamp("planned_complete_at");
        Timestamp doneAt = rs.getTimestamp("done_at");
        Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
        String preRiskStatus = rs.getString("pre_risk_status");
        return new IncidentTask(
                rs.getLong("id"), rs.getLong("incident_id"), rs.getString("task_key"),
                rs.getString("group_code"), rs.getString("title"),
                TaskStatus.valueOf(rs.getString("status")),
                plannedCompleteAt == null ? null : plannedCompleteAt.toInstant(),
                preRiskStatus == null ? null : TaskStatus.valueOf(preRiskStatus),
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
                    "INSERT INTO incident_tasks (incident_id, task_key, group_code, title, status,"
                            + " planned_complete_at, pre_risk_status,"
                            + " created_by, done_by, done_at, cancelled_by, cancelled_at,"
                            + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, task.incidentId());
            ps.setString(2, task.taskKey());
            ps.setString(3, task.groupCode());
            ps.setString(4, task.title());
            ps.setString(5, task.status().name());
            ps.setTimestamp(6, task.plannedCompleteAt() == null
                    ? null : Timestamp.from(task.plannedCompleteAt()));
            ps.setString(7, task.preRiskStatus() == null ? null : task.preRiskStatus().name());
            ps.setString(8, task.createdBy());
            ps.setString(9, task.doneBy());
            ps.setTimestamp(10, task.doneAt() == null ? null : Timestamp.from(task.doneAt()));
            ps.setString(11, task.cancelledBy());
            ps.setTimestamp(12, task.cancelledAt() == null ? null : Timestamp.from(task.cancelledAt()));
            ps.setTimestamp(13, Timestamp.from(task.createdAt()));
            ps.setTimestamp(14, Timestamp.from(task.updatedAt()));
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
     * 查询事件仍未进入终态的任务（OPEN/IN_PROGRESS/CREDENTIAL_RISK，解决门禁用），
     * 按创建顺序返回。
     */
    public List<IncidentTask> listUnfinishedByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_tasks WHERE incident_id = ?"
                + " AND status IN ('OPEN','IN_PROGRESS','CREDENTIAL_RISK') ORDER BY id",
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
     * 将 OPEN 任务条件置为 IN_PROGRESS；返回更新行数，0 表示状态已变化（并发裁决失败）。
     */
    public int markStarted(long id, Instant at) {
        return jdbc.update("UPDATE incident_tasks SET status = 'IN_PROGRESS', updated_at = ?"
                + " WHERE id = ? AND status = 'OPEN'",
                Timestamp.from(at), id);
    }

    /**
     * 将 OPEN/IN_PROGRESS 任务条件置为 DONE，记录完成人与 UTC 时刻；
     * 返回更新行数，0 表示状态已变化（如并发资质撤销先进入 CREDENTIAL_RISK）。
     */
    public int markDone(long id, String actor, Instant at) {
        return jdbc.update("UPDATE incident_tasks SET status = 'DONE', done_by = ?, done_at = ?,"
                        + " updated_at = ? WHERE id = ? AND status IN ('OPEN','IN_PROGRESS')",
                actor, Timestamp.from(at), Timestamp.from(at), id);
    }

    /**
     * 将 OPEN/IN_PROGRESS/CREDENTIAL_RISK 任务条件置为 CANCELLED，记录取消人与 UTC 时刻；
     * 返回更新行数，0 表示已进入终态。
     */
    public int markCancelled(long id, String actor, Instant at) {
        return jdbc.update("UPDATE incident_tasks SET status = 'CANCELLED', cancelled_by = ?,"
                        + " cancelled_at = ?, updated_at = ? WHERE id = ?"
                        + " AND status IN ('OPEN','IN_PROGRESS','CREDENTIAL_RISK')",
                actor, Timestamp.from(at), Timestamp.from(at), id);
    }

    /**
     * 将 OPEN/IN_PROGRESS 任务条件置为 CREDENTIAL_RISK 并记下风险前状态；
     * 返回更新行数，0 表示任务已进入终态（已完成任务不改写）。
     */
    public int markCredentialRisk(long id, Instant at) {
        return jdbc.update("UPDATE incident_tasks SET pre_risk_status = status,"
                        + " status = 'CREDENTIAL_RISK', updated_at = ?"
                        + " WHERE id = ? AND status IN ('OPEN','IN_PROGRESS')",
                Timestamp.from(at), id);
    }

    /**
     * 合格租约替换后将 CREDENTIAL_RISK 任务恢复到风险前状态并清空风险前状态列；
     * 返回更新行数，0 表示任务已不在风险状态。
     */
    public int restoreFromCredentialRisk(long id, Instant at) {
        return jdbc.update("UPDATE incident_tasks SET status = pre_risk_status,"
                        + " pre_risk_status = NULL, updated_at = ?"
                        + " WHERE id = ? AND status = 'CREDENTIAL_RISK'",
                Timestamp.from(at), id);
    }

    /**
     * 追加一条阻塞边（任务 → 阻塞事件）。(task_id, blocker_incident_id) 唯一。
     */
    public void insertBlocker(long taskId, long blockerIncidentId, Instant now) {
        jdbc.update("INSERT INTO incident_task_blockers (task_id, blocker_incident_id, created_at)"
                + " VALUES (?,?,?)", taskId, blockerIncidentId, Timestamp.from(now));
    }

    /**
     * 追加一条高危任务必需资质（按代码排序写入）。(task_id, credential_code) 唯一。
     */
    public void insertRequiredCredential(long taskId, String credentialCode, Instant now) {
        jdbc.update("INSERT INTO task_required_credentials (task_id, credential_code, created_at)"
                + " VALUES (?,?,?)", taskId, credentialCode, Timestamp.from(now));
    }

    /**
     * 查询任务必需资质集合，按代码排序返回（规范化集合，换序视为同参）。
     */
    public List<String> listRequiredCredentials(long taskId) {
        return jdbc.queryForList("SELECT credential_code FROM task_required_credentials"
                + " WHERE task_id = ? ORDER BY credential_code", String.class, taskId);
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
