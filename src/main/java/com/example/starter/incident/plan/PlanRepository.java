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
 * 方案版本、计划任务、依赖边与任务运行时状态的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行的写事务内；版本行发布后不可变，
 * 草稿编辑同事务推进 revision 计数器（合并请求以 expectedVersion 对齐）。
 */
@Repository
public class PlanRepository {

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PlanVersion> VERSION_MAPPER = (rs, n) -> mapVersion(rs);
    private static final RowMapper<PlanTask> TASK_MAPPER = (rs, n) -> new PlanTask(
            rs.getLong("id"), rs.getLong("version_id"), rs.getString("task_id"),
            rs.getString("group_code"), rs.getString("title"), rs.getString("assignee"),
            rs.getTimestamp("created_at").toInstant());
    private static final RowMapper<PlanEdge> EDGE_MAPPER = (rs, n) -> new PlanEdge(
            rs.getLong("id"), rs.getLong("version_id"), rs.getString("from_task_id"),
            rs.getString("to_incident_key"), rs.getString("to_task_id"),
            rs.getTimestamp("created_at").toInstant());
    private static final RowMapper<PlanTaskState> STATE_MAPPER = (rs, n) -> mapState(rs);

    private static PlanVersion mapVersion(ResultSet rs) throws SQLException {
        long baseVersionId = rs.getLong("base_version_id");
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return new PlanVersion(rs.getLong("id"), rs.getLong("incident_id"), rs.getInt("version_no"),
                PlanVersionStatus.valueOf(rs.getString("status")),
                rs.wasNull() ? null : baseVersionId, rs.getString("branch"), rs.getInt("revision"),
                rs.getString("merge_key"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                publishedAt == null ? null : publishedAt.toInstant());
    }

    private static PlanTaskState mapState(ResultSet rs) throws SQLException {
        Timestamp startedAt = rs.getTimestamp("started_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new PlanTaskState(rs.getLong("id"), rs.getLong("incident_id"),
                rs.getString("task_id"), PlanTaskStatus.valueOf(rs.getString("status")),
                rs.getString("assignee"), rs.getString("started_by"),
                startedAt == null ? null : startedAt.toInstant(), rs.getString("completed_by"),
                completedAt == null ? null : completedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 全局依赖图节点（事件键 + 稳定 taskId），取自各事件当前活动版本。
     */
    public record GraphNode(String incidentKey, String taskId) {
    }

    /**
     * 全局依赖图有向边：源事件内前置任务 → 目标任务（toIncidentKey 空串表示事件内部边）。
     */
    public record GraphEdge(String incidentKey, String fromTaskId, String toIncidentKey,
                            String toTaskId) {
    }

    // ---------- 方案版本 ----------

    /**
     * 插入新版本（DRAFT 或 PUBLISHED），返回生成主键。
     */
    public long insertVersion(PlanVersion version) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO plan_versions (incident_id, version_no, status, base_version_id,"
                            + " branch, revision, merge_key, created_by, created_at, published_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, version.incidentId());
            ps.setInt(2, version.versionNo());
            ps.setString(3, version.status().name());
            if (version.baseVersionId() == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, version.baseVersionId());
            }
            ps.setString(5, version.branch());
            ps.setInt(6, version.revision());
            ps.setString(7, version.mergeKey());
            ps.setString(8, version.createdBy());
            ps.setTimestamp(9, Timestamp.from(version.createdAt()));
            ps.setTimestamp(10, version.publishedAt() == null ? null
                    : Timestamp.from(version.publishedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按事件与版本号查询版本。
     */
    public Optional<PlanVersion> findVersion(long incidentId, int versionNo) {
        List<PlanVersion> rows = jdbc.query(
                "SELECT * FROM plan_versions WHERE incident_id = ? AND version_no = ?",
                VERSION_MAPPER, incidentId, versionNo);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询版本（草稿视图回查基版本号用）。
     */
    public Optional<PlanVersion> findVersionById(long versionId) {
        List<PlanVersion> rows = jdbc.query("SELECT * FROM plan_versions WHERE id = ?",
                VERSION_MAPPER, versionId);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件当前活动版本（status = PUBLISHED，每事件至多一个）。
     */
    public Optional<PlanVersion> findActive(long incidentId) {
        List<PlanVersion> rows = jdbc.query(
                "SELECT * FROM plan_versions WHERE incident_id = ? AND status = 'PUBLISHED'",
                VERSION_MAPPER, incidentId);
        return rows.stream().findFirst();
    }

    /**
     * 事件内当前最大版本号，无版本时返回 0。
     */
    public int maxVersionNo(long incidentId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM plan_versions WHERE incident_id = ?",
                Integer.class, incidentId);
        return max == null ? 0 : max;
    }

    /**
     * 更新版本状态；publishedAt 仅发布时传入非空。
     */
    public void updateVersionStatus(long versionId, PlanVersionStatus status, Instant publishedAt) {
        jdbc.update("UPDATE plan_versions SET status = ?, published_at = ? WHERE id = ?",
                status.name(), publishedAt == null ? null : Timestamp.from(publishedAt), versionId);
    }

    /**
     * 草稿编辑后推进修订计数器。
     */
    public void bumpRevision(long versionId, int revision) {
        jdbc.update("UPDATE plan_versions SET revision = ? WHERE id = ?", revision, versionId);
    }

    // ---------- 计划任务 ----------

    /**
     * 插入版本内计划任务。(version_id, task_id) 唯一约束兜底重复。
     */
    public void insertTask(long versionId, PlanTask task) {
        jdbc.update("INSERT INTO plan_tasks (version_id, task_id, group_code, title, assignee,"
                        + " created_at) VALUES (?,?,?,?,?,?)",
                versionId, task.taskId(), task.groupCode(), task.title(), task.assignee(),
                Timestamp.from(task.createdAt()));
    }

    /**
     * 查询版本全部计划任务，按稳定 taskId 排序（稳定输出）。
     */
    public List<PlanTask> listTasks(long versionId) {
        return jdbc.query("SELECT * FROM plan_tasks WHERE version_id = ? ORDER BY task_id",
                TASK_MAPPER, versionId);
    }

    /**
     * 按版本与稳定 taskId 查询计划任务。
     */
    public Optional<PlanTask> findTask(long versionId, String taskId) {
        List<PlanTask> rows = jdbc.query(
                "SELECT * FROM plan_tasks WHERE version_id = ? AND task_id = ?",
                TASK_MAPPER, versionId, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 草稿内新增/修改计划任务（按 taskId upsert 语义由服务层分解为插入或更新）。
     */
    public void updateTask(long versionId, PlanTask task) {
        jdbc.update("UPDATE plan_tasks SET group_code = ?, title = ?, assignee = ?"
                        + " WHERE version_id = ? AND task_id = ?",
                task.groupCode(), task.title(), task.assignee(), versionId, task.taskId());
    }

    /**
     * 草稿内移除计划任务。
     */
    public void deleteTask(long versionId, String taskId) {
        jdbc.update("DELETE FROM plan_tasks WHERE version_id = ? AND task_id = ?",
                versionId, taskId);
    }

    // ---------- 依赖边 ----------

    /**
     * 插入版本内依赖边。(version_id, from, to_incident_key, to) 唯一约束兜底重复。
     */
    public void insertEdge(long versionId, PlanEdge edge) {
        jdbc.update("INSERT INTO plan_edges (version_id, from_task_id, to_incident_key, to_task_id,"
                        + " created_at) VALUES (?,?,?,?,?)",
                versionId, edge.fromTaskId(), edge.toIncidentKey(), edge.toTaskId(),
                Timestamp.from(edge.createdAt()));
    }

    /**
     * 查询版本全部依赖边，按 from/目标事件/to 稳定排序。
     */
    public List<PlanEdge> listEdges(long versionId) {
        return jdbc.query("SELECT * FROM plan_edges WHERE version_id = ?"
                        + " ORDER BY from_task_id, to_incident_key, to_task_id",
                EDGE_MAPPER, versionId);
    }

    /**
     * 草稿内移除依赖边（按边身份）。
     */
    public void deleteEdge(long versionId, String fromTaskId, String toIncidentKey,
                           String toTaskId) {
        jdbc.update("DELETE FROM plan_edges WHERE version_id = ? AND from_task_id = ?"
                        + " AND to_incident_key = ? AND to_task_id = ?",
                versionId, fromTaskId, toIncidentKey, toTaskId);
    }

    // ---------- 任务运行时状态 ----------

    /**
     * 插入任务运行时状态（初始 PENDING）。
     */
    public void insertState(PlanTaskState state) {
        jdbc.update("INSERT INTO plan_task_state (incident_id, task_id, status, assignee,"
                        + " started_by, started_at, completed_by, completed_at, created_at,"
                        + " updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                state.incidentId(), state.taskId(), state.status().name(), state.assignee(),
                state.startedBy(),
                state.startedAt() == null ? null : Timestamp.from(state.startedAt()),
                state.completedBy(),
                state.completedAt() == null ? null : Timestamp.from(state.completedAt()),
                Timestamp.from(state.createdAt()), Timestamp.from(state.updatedAt()));
    }

    /**
     * 按事件与稳定 taskId 查询运行时状态。
     */
    public Optional<PlanTaskState> findState(long incidentId, String taskId) {
        List<PlanTaskState> rows = jdbc.query(
                "SELECT * FROM plan_task_state WHERE incident_id = ? AND task_id = ?",
                STATE_MAPPER, incidentId, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部运行时状态，按稳定 taskId 排序。
     */
    public List<PlanTaskState> listStates(long incidentId) {
        return jdbc.query("SELECT * FROM plan_task_state WHERE incident_id = ? ORDER BY task_id",
                STATE_MAPPER, incidentId);
    }

    /**
     * 发布时同步 PENDING 任务的计划负责人。
     */
    public void updateStateAssignee(long incidentId, String taskId, String assignee,
                                    Instant updatedAt) {
        jdbc.update("UPDATE plan_task_state SET assignee = ?, updated_at = ?"
                        + " WHERE incident_id = ? AND task_id = ?",
                assignee, Timestamp.from(updatedAt), incidentId, taskId);
    }

    /**
     * PENDING → IN_PROGRESS，记录启动人与 UTC 时刻。
     */
    public void markInProgress(long incidentId, String taskId, String actor, Instant at) {
        jdbc.update("UPDATE plan_task_state SET status = 'IN_PROGRESS', started_by = ?,"
                        + " started_at = ?, updated_at = ? WHERE incident_id = ? AND task_id = ?",
                actor, Timestamp.from(at), Timestamp.from(at), incidentId, taskId);
    }

    /**
     * IN_PROGRESS → COMPLETED，记录完成人与 UTC 时刻（完成事实不可回退）。
     */
    public void markCompleted(long incidentId, String taskId, String actor, Instant at) {
        jdbc.update("UPDATE plan_task_state SET status = 'COMPLETED', completed_by = ?,"
                        + " completed_at = ?, updated_at = ? WHERE incident_id = ? AND task_id = ?",
                actor, Timestamp.from(at), Timestamp.from(at), incidentId, taskId);
    }

    /**
     * 发布时删除被移除任务的运行时状态（仅 PENDING 任务可能被移除，
     * COMPLETED/IN_PROGRESS 由合并校验保护）。
     */
    public void deleteState(long incidentId, String taskId) {
        jdbc.update("DELETE FROM plan_task_state WHERE incident_id = ? AND task_id = ?",
                incidentId, taskId);
    }

    // ---------- 全局依赖图（跨事件环检测） ----------

    /**
     * 加载全部事件活动版本的任务节点（事件键 + 稳定 taskId）。
     */
    public List<GraphNode> listActiveGraphNodes() {
        return jdbc.query("SELECT i.incident_key, t.task_id FROM plan_tasks t"
                        + " JOIN plan_versions v ON v.id = t.version_id AND v.status = 'PUBLISHED'"
                        + " JOIN incidents i ON i.id = v.incident_id",
                (rs, n) -> new GraphNode(rs.getString(1), rs.getString(2)));
    }

    /**
     * 加载全部事件活动版本的依赖边（含跨事件边）。
     */
    public List<GraphEdge> listActiveGraphEdges() {
        return jdbc.query("SELECT i.incident_key, e.from_task_id, e.to_incident_key, e.to_task_id"
                        + " FROM plan_edges e"
                        + " JOIN plan_versions v ON v.id = e.version_id AND v.status = 'PUBLISHED'"
                        + " JOIN incidents i ON i.id = v.incident_id",
                (rs, n) -> new GraphEdge(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4)));
    }
}
