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
 * 方案、方案版本、版本任务快照与依赖边的 JDBC 仓储。
 * 所有写路径均处于先锁定方案行（SELECT ... FOR UPDATE）的写事务内，
 * 使合并发布、任务执行与草稿变更按事务提交顺序串行生效。
 */
@Repository
public class PlanRepository {

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Plan> PLAN_MAPPER = (rs, n) -> mapPlan(rs);
    private static final RowMapper<PlanVersion> VERSION_MAPPER = (rs, n) -> mapVersion(rs);
    private static final RowMapper<PlanTask> TASK_MAPPER = (rs, n) -> mapTask(rs);
    private static final RowMapper<PlanEdge> EDGE_MAPPER = (rs, n) -> new PlanEdge(
            rs.getLong("id"), rs.getLong("version_id"),
            rs.getString("from_task_id"), rs.getString("to_task_id"));

    private static Plan mapPlan(ResultSet rs) throws SQLException {
        long activeVersionId = rs.getLong("active_version_id");
        return new Plan(rs.getLong("id"), rs.getString("plan_key"),
                rs.wasNull() ? null : activeVersionId,
                rs.getTimestamp("created_at").toInstant());
    }

    private static PlanVersion mapVersion(ResultSet rs) throws SQLException {
        long baseVersionId = rs.getLong("base_version_id");
        return new PlanVersion(rs.getLong("id"), rs.getLong("plan_id"), rs.getInt("version_no"),
                PlanVersionStatus.valueOf(rs.getString("status")),
                rs.wasNull() ? null : baseVersionId,
                rs.getInt("expected_version"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static PlanTask mapTask(ResultSet rs) throws SQLException {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new PlanTask(rs.getLong("id"), rs.getLong("version_id"), rs.getString("task_id"),
                rs.getString("incident_key"), rs.getString("group_code"), rs.getString("title"),
                rs.getString("assignee"), PlanTaskStatus.valueOf(rs.getString("status")),
                rs.getString("completed_by"),
                completedAt == null ? null : completedAt.toInstant());
    }

    // ---------- 方案行 ----------

    /**
     * 按业务键查询方案（不加锁），用于只读场景。
     */
    public Optional<Plan> findPlanByKey(String planKey) {
        List<Plan> rows = jdbc.query("SELECT * FROM plans WHERE plan_key = ?",
                PLAN_MAPPER, planKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定方案行（SELECT ... FOR UPDATE），写路径串行化入口。
     */
    public Optional<Plan> lockPlanByKey(String planKey) {
        List<Plan> rows = jdbc.query("SELECT * FROM plans WHERE plan_key = ? FOR UPDATE",
                PLAN_MAPPER, planKey);
        return rows.stream().findFirst();
    }

    /**
     * 插入方案（活动版本为空，随后补写），返回生成主键。
     */
    public long insertPlan(String planKey, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO plans (plan_key, active_version_id, created_at) VALUES (?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, planKey);
            ps.setNull(2, java.sql.Types.BIGINT);
            ps.setTimestamp(3, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 推进活动版本指针；仅在持有方案行锁的发布事务内调用。
     */
    public void updateActiveVersion(long planId, long activeVersionId) {
        jdbc.update("UPDATE plans SET active_version_id = ? WHERE id = ?", activeVersionId, planId);
    }

    // ---------- 版本 ----------

    /**
     * 插入版本，返回生成主键。(plan_id, version_no) 唯一约束兜底并发插入。
     */
    public long insertVersion(PlanVersion version) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO plan_versions (plan_id, version_no, status, base_version_id,"
                            + " expected_version, created_by, created_at) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, version.planId());
            ps.setInt(2, version.versionNo());
            ps.setString(3, version.status().name());
            if (version.baseVersionId() == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, version.baseVersionId());
            }
            ps.setInt(5, version.expectedVersion());
            ps.setString(6, version.createdBy());
            ps.setTimestamp(7, Timestamp.from(version.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按方案与版本号查询版本。
     */
    public Optional<PlanVersion> findVersion(long planId, int versionNo) {
        List<PlanVersion> rows = jdbc.query(
                "SELECT * FROM plan_versions WHERE plan_id = ? AND version_no = ?",
                VERSION_MAPPER, planId, versionNo);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询版本。
     */
    public Optional<PlanVersion> findVersionById(long versionId) {
        List<PlanVersion> rows = jdbc.query(
                "SELECT * FROM plan_versions WHERE id = ?", VERSION_MAPPER, versionId);
        return rows.stream().findFirst();
    }

    /**
     * 方案内当前最大版本号，无版本时返回 0。
     */
    public int maxVersionNo(long planId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM plan_versions WHERE plan_id = ?",
                Integer.class, planId);
        return max == null ? 0 : max;
    }

    /**
     * 草稿变更加一乐观锁序号；仅当当前序号匹配时生效，返回更新行数。
     */
    public int bumpExpectedVersion(long versionId, int expectedVersion) {
        return jdbc.update("UPDATE plan_versions SET expected_version = expected_version + 1"
                + " WHERE id = ? AND expected_version = ? AND status = 'DRAFT'",
                versionId, expectedVersion);
    }

    /**
     * 将草稿标记为 MERGED（不可变）；仅 DRAFT 可流转，返回更新行数。
     */
    public int markMerged(long versionId) {
        return jdbc.update("UPDATE plan_versions SET status = 'MERGED' WHERE id = ?"
                + " AND status = 'DRAFT'", versionId);
    }

    // ---------- 版本任务快照 ----------

    /**
     * 批量替换版本任务快照：先删后插，仅在持有方案行锁的事务内调用。
     */
    public void replaceTasks(long versionId, List<TaskContent> tasks) {
        jdbc.update("DELETE FROM plan_tasks WHERE version_id = ?", versionId);
        for (TaskContent task : tasks) {
            jdbc.update("INSERT INTO plan_tasks (version_id, task_id, incident_key, group_code,"
                            + " title, assignee, status, completed_by, completed_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)",
                    versionId, task.taskId(), task.incidentKey(), task.groupCode(), task.title(),
                    task.assignee(), task.status().name(), task.completedBy(),
                    task.completedAt() == null ? null : Timestamp.from(task.completedAt()));
        }
    }

    /**
     * 查询版本全部任务快照，按 taskId 稳定排序。
     */
    public List<PlanTask> listTasks(long versionId) {
        return jdbc.query("SELECT * FROM plan_tasks WHERE version_id = ? ORDER BY task_id",
                TASK_MAPPER, versionId);
    }

    /**
     * 查询版本内单个任务快照。
     */
    public Optional<PlanTask> findTask(long versionId, String taskId) {
        List<PlanTask> rows = jdbc.query(
                "SELECT * FROM plan_tasks WHERE version_id = ? AND task_id = ?",
                TASK_MAPPER, versionId, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 推进任务执行状态（PENDING→IN_PROGRESS 或 IN_PROGRESS→COMPLETED），
     * 条件更新保证并发执行按提交顺序生效，返回更新行数。
     */
    public int updateTaskStatus(long versionId, String taskId, PlanTaskStatus from,
                                PlanTaskStatus to, String completedBy, Instant completedAt) {
        return jdbc.update("UPDATE plan_tasks SET status = ?, completed_by = ?, completed_at = ?"
                        + " WHERE version_id = ? AND task_id = ? AND status = ?",
                to.name(), completedBy,
                completedAt == null ? null : Timestamp.from(completedAt),
                versionId, taskId, from.name());
    }

    // ---------- 版本依赖边 ----------

    /**
     * 批量替换版本依赖边：先删后插，仅在持有方案行锁的事务内调用。
     */
    public void replaceEdges(long versionId, List<EdgeKey> edges) {
        jdbc.update("DELETE FROM plan_edges WHERE version_id = ?", versionId);
        for (EdgeKey edge : edges) {
            jdbc.update("INSERT INTO plan_edges (version_id, from_task_id, to_task_id)"
                    + " VALUES (?,?,?)", versionId, edge.fromTaskId(), edge.toTaskId());
        }
    }

    /**
     * 查询版本全部依赖边，按 (from_task_id, to_task_id) 稳定排序。
     */
    public List<PlanEdge> listEdges(long versionId) {
        return jdbc.query("SELECT * FROM plan_edges WHERE version_id = ?"
                + " ORDER BY from_task_id, to_task_id", EDGE_MAPPER, versionId);
    }
}
