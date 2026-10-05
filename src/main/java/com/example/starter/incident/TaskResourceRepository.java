package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 任务资源租约及资源串行锁的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行的写事务内；分配与续期额外持有 resource_locks
 * 单行锁（按 resource_key 排序加锁），串行化占用校验与写入，
 * 保证同一资源并发分配/续期不产生重叠授权。
 */
@Repository
public class TaskResourceRepository {

    private final JdbcTemplate jdbc;

    public TaskResourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TaskResourceAssignment> MAPPER = (rs, n) -> map(rs);

    private static TaskResourceAssignment map(ResultSet rs) throws SQLException {
        return new TaskResourceAssignment(
                rs.getLong("id"), rs.getLong("task_id"), rs.getLong("incident_id"),
                rs.getString("resource_key"), rs.getLong("authorization_version"),
                rs.getString("assigned_by"), rs.getTimestamp("assigned_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 插入新租约，返回生成主键。uk_assignment_task 唯一约束兜底并发重复分配。
     */
    public long insert(TaskResourceAssignment assignment) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO task_resource_assignments (task_id, incident_id, resource_key,"
                            + " authorization_version, assigned_by, assigned_at, expires_at,"
                            + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, assignment.taskId());
            ps.setLong(2, assignment.incidentId());
            ps.setString(3, assignment.resourceKey());
            ps.setLong(4, assignment.authorizationVersion());
            ps.setString(5, assignment.assignedBy());
            ps.setTimestamp(6, Timestamp.from(assignment.assignedAt()));
            ps.setTimestamp(7, Timestamp.from(assignment.expiresAt()));
            ps.setTimestamp(8, Timestamp.from(assignment.createdAt()));
            ps.setTimestamp(9, Timestamp.from(assignment.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按任务查询当前租约（每任务至多一条）。
     */
    public Optional<TaskResourceAssignment> findByTaskId(long taskId) {
        List<TaskResourceAssignment> rows = jdbc.query(
                "SELECT * FROM task_resource_assignments WHERE task_id = ?", MAPPER, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 按任务 id 集合批量查询当前租约（依赖闭包校验用）。
     */
    public List<TaskResourceAssignment> findByTaskIds(Collection<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(",");
        taskIds.forEach(id -> placeholders.add("?"));
        return jdbc.query("SELECT * FROM task_resource_assignments WHERE task_id IN ("
                        + placeholders + ") ORDER BY id", MAPPER, taskIds.toArray());
    }

    /**
     * 查询事件全部租约（诊断视图用），按落库顺序返回。
     */
    public List<TaskResourceAssignment> listByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM task_resource_assignments WHERE incident_id = ?"
                + " ORDER BY id", MAPPER, incidentId);
    }

    /**
     * 查询指定资源在给定时刻仍有效的租约（任务未终态且 expires_at > now），
     * 用于占用冲突校验。区间左闭右开：now ≥ expires_at 的租约不再占用。
     */
    public List<TaskResourceAssignment> listActiveByResource(String resourceKey, Instant now) {
        return jdbc.query("SELECT a.* FROM task_resource_assignments a"
                        + " JOIN incident_tasks t ON t.id = a.task_id"
                        + " WHERE a.resource_key = ? AND a.expires_at > ?"
                        + " AND t.status NOT IN ('DONE','CANCELLED') ORDER BY a.id",
                MAPPER, resourceKey, Timestamp.from(now));
    }

    /**
     * 续期写回：更新授权版本与到期时刻。assigned_by 保持原分配人不变。
     */
    public void updateAuthorization(long id, long newVersion, Instant newExpiresAt,
                                    Instant updatedAt) {
        jdbc.update("UPDATE task_resource_assignments SET authorization_version = ?,"
                        + " expires_at = ?, updated_at = ? WHERE id = ?",
                newVersion, Timestamp.from(newExpiresAt), Timestamp.from(updatedAt), id);
    }

    /**
     * 持有资源串行锁（resource_locks 单行 SELECT ... FOR UPDATE）。
     * 锁行不存在时先插入；并发首次插入由主键约束串行化。
     */
    public void lockResource(String resourceKey) {
        try {
            jdbc.update("INSERT INTO resource_locks (resource_key) VALUES (?)", resourceKey);
        } catch (DuplicateKeyException e) {
            // 锁行已存在（含并发事务已提交），继续加锁
        }
        jdbc.queryForObject("SELECT resource_key FROM resource_locks WHERE resource_key = ?"
                + " FOR UPDATE", String.class, resourceKey);
    }
}
