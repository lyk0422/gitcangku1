package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 分组处置任务及跨事件阻塞边的 JDBC 仓储。
 * 任务写路径与既有约定一致：先锁定所属事件行，同事务内完成幂等占位、校验与写入；
 * 创建任务（含跨事件边写入）前先锁定 task_graph_lock 固定单行，使环检测与边写入全局串行一致。
 */
@Repository
public class TaskRepository {

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Task> TASK_MAPPER = (rs, n) -> mapTask(rs);
    private static final RowMapper<TaskBlock> BLOCK_MAPPER = (rs, n) -> new TaskBlock(
            rs.getLong("id"), rs.getLong("task_id"), rs.getLong("blocked_incident_id"),
            rs.getString("blocked_incident_key"));

    private static Task mapTask(ResultSet rs) throws SQLException {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
        return new Task(
                rs.getLong("id"), rs.getLong("incident_id"), rs.getString("task_key"),
                rs.getString("group_code"), rs.getString("title"),
                TaskStatus.valueOf(rs.getString("status")), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant(),
                cancelledAt == null ? null : cancelledAt.toInstant());
    }

    /**
     * 锁定跨事件依赖图全局写锁（固定单行 FOR UPDATE），使创建任务的环检测与边写入串行化。
     */
    public void lockGraph() {
        jdbc.queryForObject("SELECT id FROM task_graph_lock WHERE id = 1 FOR UPDATE", Long.class);
    }

    /**
     * 插入处置任务，返回生成主键。
     */
    public long insertTask(Task task) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_tasks (incident_id, task_key, group_code, title, status,"
                            + " created_by, created_at, updated_at, completed_at, cancelled_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, task.incidentId());
            ps.setString(2, task.taskKey());
            ps.setString(3, task.groupCode());
            ps.setString(4, task.title());
            ps.setString(5, task.status().name());
            ps.setString(6, task.createdBy());
            ps.setTimestamp(7, Timestamp.from(task.createdAt()));
            ps.setTimestamp(8, Timestamp.from(task.updatedAt()));
            ps.setTimestamp(9, task.completedAt() == null ? null : Timestamp.from(task.completedAt()));
            ps.setTimestamp(10, task.cancelledAt() == null ? null : Timestamp.from(task.cancelledAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询事件内指定 taskKey 的任务。
     */
    public Optional<Task> findTask(long incidentId, String taskKey) {
        List<Task> rows = jdbc.query(
                "SELECT * FROM incident_tasks WHERE incident_id = ? AND task_key = ?",
                TASK_MAPPER, incidentId, taskKey);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询任务。
     */
    public Optional<Task> findTaskById(long taskId) {
        List<Task> rows = jdbc.query("SELECT * FROM incident_tasks WHERE id = ?",
                TASK_MAPPER, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部任务，按落库顺序返回。
     */
    public List<Task> listTasksByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_tasks WHERE incident_id = ? ORDER BY id",
                TASK_MAPPER, incidentId);
    }

    /**
     * 查询事件仍 OPEN 的任务，按 groupCode、taskKey 排序，用于解决门禁返回未完成项。
     */
    public List<Task> listOpenTasksByIncident(long incidentId) {
        return jdbc.query(
                "SELECT * FROM incident_tasks WHERE incident_id = ? AND status = 'OPEN'"
                        + " ORDER BY group_code, task_key",
                TASK_MAPPER, incidentId);
    }

    /**
     * 统计事件任务数量，用于“每事件最多 20 个”校验。
     */
    public int countTasksByIncident(long incidentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_tasks WHERE incident_id = ?", Integer.class, incidentId);
        return count == null ? 0 : count;
    }

    /**
     * 将任务原子置为 DONE；条件包含 status='OPEN'，终态任务更新行数为 0。
     */
    public int completeTask(long taskId, Instant completedAt) {
        return jdbc.update("UPDATE incident_tasks SET status = 'DONE', completed_at = ?,"
                        + " cancelled_at = NULL, updated_at = ? WHERE id = ? AND status = 'OPEN'",
                Timestamp.from(completedAt), Timestamp.from(completedAt), taskId);
    }

    /**
     * 将任务原子置为 CANCELLED；条件包含 status='OPEN'，终态任务更新行数为 0。
     */
    public int cancelTask(long taskId, Instant cancelledAt) {
        return jdbc.update("UPDATE incident_tasks SET status = 'CANCELLED', cancelled_at = ?,"
                        + " updated_at = ? WHERE id = ? AND status = 'OPEN'",
                Timestamp.from(cancelledAt), Timestamp.from(cancelledAt), taskId);
    }

    /**
     * 插入一条跨事件阻塞边。
     */
    public void insertBlock(TaskBlock block) {
        jdbc.update("INSERT INTO incident_task_blocks (task_id, blocked_incident_id,"
                        + " blocked_incident_key) VALUES (?,?,?)",
                block.taskId(), block.blockedIncidentId(), block.blockedIncidentKey());
    }

    /**
     * 查询任务的全部跨事件阻塞边，按落库顺序返回。
     */
    public List<TaskBlock> listBlocksByTask(long taskId) {
        return jdbc.query("SELECT * FROM incident_task_blocks WHERE task_id = ? ORDER BY id",
                BLOCK_MAPPER, taskId);
    }

    /**
     * 读取整张跨事件依赖图的有向边：source_incident_id（任务所属事件）→ blocked_incident_id（目标事件）。
     * 仅在持有 task_graph_lock 全局写锁的事务内调用，读到的是已提交的最新图。
     */
    public List<TaskDependencyEdge> listEdges() {
        return jdbc.query(
                "SELECT t.incident_id AS source_incident_id, b.blocked_incident_id"
                        + " FROM incident_task_blocks b"
                        + " JOIN incident_tasks t ON t.id = b.task_id",
                (rs, n) -> new TaskDependencyEdge(
                        rs.getLong("source_incident_id"), rs.getLong("blocked_incident_id")));
    }
}
