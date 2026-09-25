package com.example.starter.incident.plan;

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
 * 方案版本、版本任务、依赖边与任务执行态的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行的写事务内；合并发布额外持有 task_graph_lock
 * 全局锁，使跨事件环检测与边写入相对其他发布/创建串行化。
 */
@Repository
public class PlanVersionRepository {

    private final JdbcTemplate jdbc;

    public PlanVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PlanVersion> VERSION_MAPPER = (rs, n) -> mapVersion(rs);
    private static final RowMapper<PlanTask> TASK_MAPPER = (rs, n) -> mapTask(rs);
    private static final RowMapper<PlanEdge> EDGE_MAPPER = (rs, n) -> new PlanEdge(
            rs.getLong("id"), rs.getLong("version_id"), rs.getString("from_task_id"),
            rs.getString("to_incident_key"), rs.getString("to_task_id"),
            rs.getTimestamp("created_at").toInstant());
    private static final RowMapper<PlanTaskExecution> EXECUTION_MAPPER = (rs, n) -> mapExecution(rs);

    private static PlanVersion mapVersion(ResultSet rs) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return new PlanVersion(rs.getLong("id"), rs.getString("incident_key"),
                rs.getInt("version_no"), PlanVersionStatus.valueOf(rs.getString("status")),
                nullableLong(rs, "base_version_id"), rs.getString("branch_side"),
                rs.getInt("revision"), nullableLong(rs, "left_version_id"),
                nullableLong(rs, "right_version_id"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                publishedAt == null ? null : publishedAt.toInstant());
    }

    private static PlanTask mapTask(ResultSet rs) throws SQLException {
        return new PlanTask(rs.getLong("id"), rs.getLong("version_id"),
                rs.getString("task_id"), rs.getString("title"), rs.getString("assignee"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static PlanTaskExecution mapExecution(ResultSet rs) throws SQLException {
        Timestamp startedAt = rs.getTimestamp("started_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new PlanTaskExecution(rs.getLong("id"), rs.getString("incident_key"),
                rs.getString("task_id"), PlanTaskStatus.valueOf(rs.getString("status")),
                rs.getString("assignee"), rs.getString("started_by"),
                startedAt == null ? null : startedAt.toInstant(),
                rs.getString("completed_by"),
                completedAt == null ? null : completedAt.toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * 插入方案版本，返回生成主键。(incident_key, version_no) 唯一约束兜底并发。
     */
    public long insertVersion(PlanVersion version) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO plan_versions (incident_key, version_no, status, base_version_id,"
                            + " branch_side, revision, left_version_id, right_version_id,"
                            + " created_by, created_at, published_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, version.incidentKey());
            ps.setInt(2, version.versionNo());
            ps.setString(3, version.status().name());
            setNullableLong(ps, 4, version.baseVersionId());
            ps.setString(5, version.branchSide());
            ps.setInt(6, version.revision());
            setNullableLong(ps, 7, version.leftVersionId());
            setNullableLong(ps, 8, version.rightVersionId());
            ps.setString(9, version.createdBy());
            ps.setTimestamp(10, Timestamp.from(version.createdAt()));
            ps.setTimestamp(11, version.publishedAt() == null ? null
                    : Timestamp.from(version.publishedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private static void setNullableLong(java.sql.PreparedStatement ps, int index, Long value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    /**
     * 按主键查询版本。
     */
    public Optional<PlanVersion> findVersion(long id) {
        List<PlanVersion> rows = jdbc.query("SELECT * FROM plan_versions WHERE id = ?",
                VERSION_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件当前活动版本：最大版本号的 PUBLISHED 版本。
     */
    public Optional<PlanVersion> findActiveVersion(String incidentKey) {
        List<PlanVersion> rows = jdbc.query(
                "SELECT * FROM plan_versions WHERE incident_key = ? AND status = 'PUBLISHED'"
                        + " ORDER BY version_no DESC LIMIT 1",
                VERSION_MAPPER, incidentKey);
        return rows.stream().findFirst();
    }

    /**
     * 事件当前最大版本号，无版本时返回 0。
     */
    public int maxVersionNo(String incidentKey) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM plan_versions WHERE incident_key = ?",
                Integer.class, incidentKey);
        return max == null ? 0 : max;
    }

    /**
     * 查询基准版本的全部 DRAFT 分支，按创建顺序返回。
     */
    public List<PlanVersion> listDrafts(long baseVersionId) {
        return jdbc.query("SELECT * FROM plan_versions WHERE base_version_id = ?"
                + " AND status = 'DRAFT' ORDER BY id", VERSION_MAPPER, baseVersionId);
    }

    /**
     * 草稿修订计数 +1（每次草稿修改调用）。
     */
    public void bumpRevision(long versionId) {
        jdbc.update("UPDATE plan_versions SET revision = revision + 1 WHERE id = ?", versionId);
    }

    /**
     * 将 DRAFT 分支标记为 MERGED（合并成功后原分支不可变）。
     */
    public void markMerged(long versionId) {
        jdbc.update("UPDATE plan_versions SET status = 'MERGED' WHERE id = ?", versionId);
    }

    /**
     * 插入版本任务，返回生成主键。(version_id, task_id) 唯一约束兜底。
     */
    public long insertTask(PlanTask task) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO plan_tasks (version_id, task_id, title, assignee,"
                            + " created_at, updated_at) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, task.versionId());
            ps.setString(2, task.taskId());
            ps.setString(3, task.title());
            ps.setString(4, task.assignee());
            ps.setTimestamp(5, Timestamp.from(task.createdAt()));
            ps.setTimestamp(6, Timestamp.from(task.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询版本全部任务，按 taskId 排序（稳定顺序）。
     */
    public List<PlanTask> listTasks(long versionId) {
        return jdbc.query("SELECT * FROM plan_tasks WHERE version_id = ? ORDER BY task_id",
                TASK_MAPPER, versionId);
    }

    /**
     * 按稳定 taskId 查询版本任务。
     */
    public Optional<PlanTask> findTask(long versionId, String taskId) {
        List<PlanTask> rows = jdbc.query(
                "SELECT * FROM plan_tasks WHERE version_id = ? AND task_id = ?",
                TASK_MAPPER, versionId, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 更新草稿任务规划字段。
     */
    public void updateTaskFields(long versionId, String taskId, String title, String assignee,
                                 Instant updatedAt) {
        jdbc.update("UPDATE plan_tasks SET title = ?, assignee = ?, updated_at = ?"
                        + " WHERE version_id = ? AND task_id = ?",
                title, assignee, Timestamp.from(updatedAt), versionId, taskId);
    }

    /**
     * 删除草稿任务。
     */
    public void deleteTask(long versionId, String taskId) {
        jdbc.update("DELETE FROM plan_tasks WHERE version_id = ? AND task_id = ?",
                versionId, taskId);
    }

    /**
     * 插入依赖边，返回生成主键。(version_id, from, to_incident, to) 唯一约束兜底重复边。
     */
    public long insertEdge(PlanEdge edge) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO plan_edges (version_id, from_task_id, to_incident_key, to_task_id,"
                            + " created_at) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, edge.versionId());
            ps.setString(2, edge.fromTaskId());
            ps.setString(3, edge.toIncidentKey());
            ps.setString(4, edge.toTaskId());
            ps.setTimestamp(5, Timestamp.from(edge.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询版本全部依赖边，按规范键排序（稳定顺序）。
     */
    public List<PlanEdge> listEdges(long versionId) {
        return jdbc.query("SELECT * FROM plan_edges WHERE version_id = ?"
                + " ORDER BY from_task_id, to_incident_key, to_task_id", EDGE_MAPPER, versionId);
    }

    /**
     * 删除草稿边，返回删除行数（0 表示边不存在）。
     */
    public int deleteEdge(long versionId, String fromTaskId, String toIncidentKey, String toTaskId) {
        return jdbc.update("DELETE FROM plan_edges WHERE version_id = ? AND from_task_id = ?"
                + " AND to_incident_key = ? AND to_task_id = ?",
                versionId, fromTaskId, toIncidentKey, toTaskId);
    }

    /**
     * 级联删除草稿任务相关边（作为依赖方，或作为内部边的前置任务）。
     */
    public void deleteEdgesOfTask(long versionId, String incidentKey, String taskId) {
        jdbc.update("DELETE FROM plan_edges WHERE version_id = ? AND (from_task_id = ?"
                        + " OR (to_incident_key = ? AND to_task_id = ?))",
                versionId, taskId, incidentKey, taskId);
    }

    /**
     * 全局活动边集：各事件当前活动版本（最大版本号 PUBLISHED）的全部边。
     * 调用前必须已持有依赖图全局锁，用于合并发布的全局环检测。
     */
    public List<GlobalEdge> listActiveEdgesGlobally() {
        return jdbc.query("SELECT v.incident_key, e.from_task_id, e.to_incident_key, e.to_task_id"
                        + " FROM plan_edges e JOIN plan_versions v ON v.id = e.version_id"
                        + " WHERE v.status = 'PUBLISHED' AND v.version_no = ("
                        + " SELECT MAX(v2.version_no) FROM plan_versions v2"
                        + " WHERE v2.incident_key = v.incident_key AND v2.status = 'PUBLISHED')",
                (rs, n) -> new GlobalEdge(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)));
    }

    /**
     * 全局依赖边：sourceIncidentKey/fromTaskId（依赖方）→ toIncidentKey/toTaskId（前置）。
     */
    public record GlobalEdge(String sourceIncidentKey, String fromTaskId,
                             String toIncidentKey, String toTaskId) {
    }

    /**
     * 查询任务执行态（无行表示 PENDING）。
     */
    public Optional<PlanTaskExecution> findExecution(String incidentKey, String taskId) {
        List<PlanTaskExecution> rows = jdbc.query(
                "SELECT * FROM plan_task_executions WHERE incident_key = ? AND task_id = ?",
                EXECUTION_MAPPER, incidentKey, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部执行态记录，按 taskId 排序。
     */
    public List<PlanTaskExecution> listExecutions(String incidentKey) {
        return jdbc.query("SELECT * FROM plan_task_executions WHERE incident_key = ?"
                + " ORDER BY task_id", EXECUTION_MAPPER, incidentKey);
    }

    /**
     * 写入开始执行记录（PENDING → IN_PROGRESS）。
     */
    public void insertExecution(PlanTaskExecution execution) {
        jdbc.update("INSERT INTO plan_task_executions (incident_key, task_id, status, assignee,"
                        + " started_by, started_at, completed_by, completed_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                execution.incidentKey(), execution.taskId(), execution.status().name(),
                execution.assignee(), execution.startedBy(),
                execution.startedAt() == null ? null : Timestamp.from(execution.startedAt()),
                execution.completedBy(),
                execution.completedAt() == null ? null : Timestamp.from(execution.completedAt()),
                Timestamp.from(execution.updatedAt()));
    }

    /**
     * 完成执行记录（IN_PROGRESS → COMPLETED），返回更新行数（0 表示并发已变更）。
     */
    public int completeExecution(String incidentKey, String taskId, String completedBy,
                                 Instant completedAt) {
        return jdbc.update("UPDATE plan_task_executions SET status = 'COMPLETED',"
                        + " completed_by = ?, completed_at = ?, updated_at = ?"
                        + " WHERE incident_key = ? AND task_id = ? AND status = 'IN_PROGRESS'",
                completedBy, Timestamp.from(completedAt), Timestamp.from(completedAt),
                incidentKey, taskId);
    }
}
