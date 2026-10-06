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
 * 资源注册表与任务资源租约的 JDBC 仓储。
 * 并发约定：所有写路径先锁定事件行，再以 resource_key 行锁（SELECT ... FOR UPDATE）
 * 串行化占用判定与租约写入，保证同一资源的并发分配/续期/回收按事务提交顺序裁决，
 * 不产生重叠授权。
 */
@Repository
public class ResourceRepository {

    private final JdbcTemplate jdbc;

    public ResourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 资源注册行：holder* 为当前持有方；空闲时全部为 null。
     */
    public record ResourceRow(long id, String resourceKey, Long holderAssignmentId,
                              Long holderIncidentId, Long holderTaskId) {
    }

    private static final RowMapper<ResourceRow> RESOURCE_MAPPER = (rs, n) -> new ResourceRow(
            rs.getLong("id"), rs.getString("resource_key"),
            rs.getObject("holder_assignment_id", Long.class),
            rs.getObject("holder_incident_id", Long.class),
            rs.getObject("holder_task_id", Long.class));

    private static final RowMapper<ResourceAssignment> ASSIGNMENT_MAPPER = (rs, n) -> mapAssignment(rs);

    private static ResourceAssignment mapAssignment(ResultSet rs) throws SQLException {
        Timestamp releasedAt = rs.getTimestamp("released_at");
        return new ResourceAssignment(
                rs.getLong("id"), rs.getLong("task_id"), rs.getLong("incident_id"),
                rs.getString("resource_key"), rs.getInt("authorization_version"),
                rs.getString("assigned_by"), rs.getTimestamp("assigned_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                AssignmentStatus.valueOf(rs.getString("status")),
                rs.getString("released_by"),
                releasedAt == null ? null : releasedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 锁定资源注册行（不存在则先插入），返回持有方快照。
     * 并发首次插入由 resource_key 唯一约束串行化。
     */
    public ResourceRow lockResource(String resourceKey, Instant now) {
        try {
            jdbc.update("INSERT INTO incident_resources (resource_key, holder_assignment_id,"
                            + " holder_incident_id, holder_task_id, created_at, updated_at)"
                            + " VALUES (?,NULL,NULL,NULL,?,?)",
                    resourceKey, Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            // 注册行已存在（含并发事务已提交），继续加锁
        }
        return jdbc.queryForObject(
                "SELECT * FROM incident_resources WHERE resource_key = ? FOR UPDATE",
                RESOURCE_MAPPER, resourceKey);
    }

    /**
     * 按资源键查询注册行（不加锁），用于只读场景。
     */
    public Optional<ResourceRow> findResource(String resourceKey) {
        List<ResourceRow> rows = jdbc.query(
                "SELECT * FROM incident_resources WHERE resource_key = ?",
                RESOURCE_MAPPER, resourceKey);
        return rows.stream().findFirst();
    }

    /**
     * 写入资源持有方（调用前必须已持有该资源行锁）。
     */
    public void updateHolder(String resourceKey, long assignmentId, long incidentId,
                             long taskId, Instant now) {
        jdbc.update("UPDATE incident_resources SET holder_assignment_id = ?,"
                        + " holder_incident_id = ?, holder_task_id = ?, updated_at = ?"
                        + " WHERE resource_key = ?",
                assignmentId, incidentId, taskId, Timestamp.from(now), resourceKey);
    }

    /**
     * 清空资源持有方（仅当当前持有者为指定租约时），返回更新行数。
     */
    public int clearHolder(String resourceKey, long assignmentId, Instant now) {
        return jdbc.update("UPDATE incident_resources SET holder_assignment_id = NULL,"
                        + " holder_incident_id = NULL, holder_task_id = NULL, updated_at = ?"
                        + " WHERE resource_key = ? AND holder_assignment_id = ?",
                Timestamp.from(now), resourceKey, assignmentId);
    }

    /**
     * 插入 ACTIVE 租约，返回生成主键。
     */
    public long insertAssignment(ResourceAssignment a) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO task_resource_assignments (task_id, incident_id, resource_key,"
                            + " authorization_version, assigned_by, assigned_at, expires_at, status,"
                            + " released_by, released_at, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, a.taskId());
            ps.setLong(2, a.incidentId());
            ps.setString(3, a.resourceKey());
            ps.setInt(4, a.authorizationVersion());
            ps.setString(5, a.assignedBy());
            ps.setTimestamp(6, Timestamp.from(a.assignedAt()));
            ps.setTimestamp(7, Timestamp.from(a.expiresAt()));
            ps.setString(8, a.status().name());
            ps.setString(9, a.releasedBy());
            ps.setTimestamp(10, a.releasedAt() == null ? null : Timestamp.from(a.releasedAt()));
            ps.setTimestamp(11, Timestamp.from(a.createdAt()));
            ps.setTimestamp(12, Timestamp.from(a.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询任务的当前 ACTIVE 租约（每任务至多一条，由事件行锁保证）。
     */
    public Optional<ResourceAssignment> findActiveByTask(long taskId) {
        List<ResourceAssignment> rows = jdbc.query(
                "SELECT * FROM task_resource_assignments WHERE task_id = ? AND status = 'ACTIVE'",
                ASSIGNMENT_MAPPER, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 查询一组事件下全部 ACTIVE 租约（依赖闭包校验用），按 id 升序返回。
     */
    public List<ResourceAssignment> listActiveByIncidents(Collection<Long> incidentIds) {
        if (incidentIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(",");
        incidentIds.forEach(id -> placeholders.add("?"));
        return jdbc.query("SELECT * FROM task_resource_assignments WHERE status = 'ACTIVE'"
                        + " AND incident_id IN (" + placeholders + ") ORDER BY id",
                ASSIGNMENT_MAPPER, incidentIds.toArray());
    }

    /**
     * 续期：写入新授权版本与新到期时刻（调用前必须完成闭包校验并持有资源行锁）。
     */
    public void renewAssignment(long id, int newVersion, Instant newExpiresAt, Instant now) {
        jdbc.update("UPDATE task_resource_assignments SET authorization_version = ?,"
                        + " expires_at = ?, updated_at = ? WHERE id = ? AND status = 'ACTIVE'",
                newVersion, Timestamp.from(newExpiresAt), Timestamp.from(now), id);
    }

    /**
     * 回收租约：置为 RELEASED 并记录回收人与 UTC 时刻。
     */
    public void markReleased(long id, String actor, Instant now) {
        jdbc.update("UPDATE task_resource_assignments SET status = 'RELEASED', released_by = ?,"
                        + " released_at = ?, updated_at = ? WHERE id = ? AND status = 'ACTIVE'",
                actor, Timestamp.from(now), Timestamp.from(now), id);
    }

    /**
     * 按租约 id 查询（不加锁），用于视图组装。
     */
    public Optional<ResourceAssignment> findAssignment(long id) {
        List<ResourceAssignment> rows = jdbc.query(
                "SELECT * FROM task_resource_assignments WHERE id = ?", ASSIGNMENT_MAPPER, id);
        return rows.stream().findFirst();
    }
}
