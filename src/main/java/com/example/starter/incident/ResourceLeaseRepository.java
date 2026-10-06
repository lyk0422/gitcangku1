package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 资源租约与资源使用证据的 JDBC 仓储。
 * 并发约定：分配路径先锁定事件行，再对资源行 SELECT ... FOR UPDATE（固定加锁顺序）；
 * 续期/回收在事件行锁内按提交顺序生效。leased_resources.active_lease_id 保证
 * 同一资源至多一条活动租约，并发分配/续期不产生重叠授权。
 * 证据（resource_lease_events）只追加不改写。
 */
@Repository
public class ResourceLeaseRepository {

    private final JdbcTemplate jdbc;

    public ResourceLeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LeasedResource> RESOURCE_MAPPER = (rs, n) -> {
        long activeLeaseId = rs.getLong("active_lease_id");
        Long activeLease = rs.wasNull() ? null : activeLeaseId;
        return new LeasedResource(rs.getLong("id"), rs.getString("resource_key"),
                activeLease, rs.getTimestamp("created_at").toInstant());
    };

    private static final RowMapper<ResourceLease> LEASE_MAPPER = (rs, n) -> mapLease(rs);

    private static ResourceLease mapLease(ResultSet rs) throws SQLException {
        Timestamp endedAt = rs.getTimestamp("ended_at");
        return new ResourceLease(
                rs.getLong("id"), rs.getLong("resource_id"), rs.getString("resource_key"),
                rs.getLong("incident_id"), rs.getLong("task_id"), rs.getInt("lease_version"),
                rs.getString("auth_version"), rs.getString("assigned_by"),
                rs.getString("authorized_by"), rs.getString("authorizer_role"),
                rs.getTimestamp("assigned_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                LeaseStatus.valueOf(rs.getString("status")),
                endedAt == null ? null : endedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    private static final RowMapper<LeaseEvent> EVENT_MAPPER = (rs, n) -> {
        long leaseId = rs.getLong("lease_id");
        Long lease = rs.wasNull() ? null : leaseId;
        return new LeaseEvent(rs.getLong("id"),
                LeaseEventType.valueOf(rs.getString("event_type")), rs.getString("resource_key"),
                rs.getLong("incident_id"), rs.getLong("task_id"), lease,
                rs.getString("old_auth_version"), rs.getString("new_auth_version"),
                rs.getString("actor"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("detail"));
    };

    /**
     * 按资源键查询并锁定资源行（SELECT ... FOR UPDATE）；资源不存在时先注册。
     * 并发首次注册由 resource_key 唯一约束串行化。
     */
    public LeasedResource lockResource(String resourceKey, Instant now) {
        try {
            jdbc.update("INSERT INTO leased_resources (resource_key, active_lease_id, created_at)"
                    + " VALUES (?,NULL,?)", resourceKey, Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            // 资源已注册（含并发事务已提交），继续加锁
        }
        return jdbc.queryForObject("SELECT * FROM leased_resources WHERE resource_key = ? FOR UPDATE",
                RESOURCE_MAPPER, resourceKey);
    }

    /**
     * 按资源键查询资源（不加锁），用于只读场景。
     */
    public Optional<LeasedResource> findResource(String resourceKey) {
        List<LeasedResource> rows = jdbc.query(
                "SELECT * FROM leased_resources WHERE resource_key = ?", RESOURCE_MAPPER, resourceKey);
        return rows.stream().findFirst();
    }

    /**
     * 插入新租约版本（ACTIVE），返回生成主键。(task_id, lease_version) 唯一约束兜底。
     */
    public long insertLease(ResourceLease lease) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO resource_leases (resource_id, resource_key, incident_id, task_id,"
                            + " lease_version, auth_version, assigned_by, authorized_by, authorizer_role,"
                            + " assigned_at, expires_at, status, ended_at, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, lease.resourceId());
            ps.setString(2, lease.resourceKey());
            ps.setLong(3, lease.incidentId());
            ps.setLong(4, lease.taskId());
            ps.setInt(5, lease.leaseVersion());
            ps.setString(6, lease.authVersion());
            ps.setString(7, lease.assignedBy());
            ps.setString(8, lease.authorizedBy());
            ps.setString(9, lease.authorizerRole());
            ps.setTimestamp(10, Timestamp.from(lease.assignedAt()));
            ps.setTimestamp(11, Timestamp.from(lease.expiresAt()));
            ps.setString(12, lease.status().name());
            ps.setTimestamp(13, lease.endedAt() == null ? null : Timestamp.from(lease.endedAt()));
            ps.setTimestamp(14, Timestamp.from(lease.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询任务的当前活动租约（不存在返回空）。
     */
    public Optional<ResourceLease> findActiveByTask(long taskId) {
        List<ResourceLease> rows = jdbc.query(
                "SELECT * FROM resource_leases WHERE task_id = ? AND status = 'ACTIVE'",
                LEASE_MAPPER, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 按租约 id 查询（不加锁）。
     */
    public Optional<ResourceLease> findLease(long leaseId) {
        List<ResourceLease> rows = jdbc.query(
                "SELECT * FROM resource_leases WHERE id = ?", LEASE_MAPPER, leaseId);
        return rows.stream().findFirst();
    }

    /**
     * 查询任务全部租约版本，按版本升序返回。
     */
    public List<ResourceLease> listByTask(long taskId) {
        return jdbc.query("SELECT * FROM resource_leases WHERE task_id = ? ORDER BY lease_version",
                LEASE_MAPPER, taskId);
    }

    /**
     * 查询事件内持有活动租约的 OPEN 任务 id 及其租约（资源依赖闭包），按任务 id 升序返回。
     */
    public List<ResourceLease> listActiveLeasesOfOpenTasks(long incidentId) {
        return jdbc.query("SELECT l.* FROM resource_leases l"
                        + " JOIN incident_tasks t ON t.id = l.task_id"
                        + " WHERE l.incident_id = ? AND l.status = 'ACTIVE' AND t.status = 'OPEN'"
                        + " ORDER BY l.task_id",
                LEASE_MAPPER, incidentId);
    }

    /**
     * 查询事件内活动租约已到期的 OPEN 任务租约（expires_at <= now），用于命令时到期物化。
     */
    public List<ResourceLease> listExpiredActiveLeases(long incidentId, Instant now) {
        return jdbc.query("SELECT l.* FROM resource_leases l"
                        + " JOIN incident_tasks t ON t.id = l.task_id"
                        + " WHERE l.incident_id = ? AND l.status = 'ACTIVE' AND t.status = 'OPEN'"
                        + " AND l.expires_at <= ? ORDER BY l.task_id",
                LEASE_MAPPER, incidentId, Timestamp.from(now));
    }

    /**
     * 结束指定活动租约：置为 SUPERSEDED/RELEASED/EXPIRED 并写入结束时刻。
     * 条件包含 status='ACTIVE'，并发下重复结束返回 0。
     */
    public int endLease(long leaseId, LeaseStatus target, Instant endedAt) {
        return jdbc.update("UPDATE resource_leases SET status = ?, ended_at = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'",
                target.name(), Timestamp.from(endedAt), leaseId);
    }

    /**
     * 将资源的活动租约指针指向新租约（分配或续期时调用）。
     */
    public void pointActiveLease(long resourceId, long leaseId) {
        jdbc.update("UPDATE leased_resources SET active_lease_id = ? WHERE id = ?",
                leaseId, resourceId);
    }

    /**
     * 释放资源的活动租约指针（条件更新：仅当指针仍指向该租约时置空）。
     */
    public void clearActiveLease(long resourceId, long leaseId) {
        jdbc.update("UPDATE leased_resources SET active_lease_id = NULL"
                + " WHERE id = ? AND active_lease_id = ?", resourceId, leaseId);
    }

    /**
     * 追加资源使用证据（只追加不改写）。
     */
    public void insertEvent(LeaseEvent event) {
        jdbc.update("INSERT INTO resource_lease_events (event_type, resource_key, incident_id,"
                        + " task_id, lease_id, old_auth_version, new_auth_version, actor,"
                        + " occurred_at, detail) VALUES (?,?,?,?,?,?,?,?,?,?)",
                event.eventType().name(), event.resourceKey(), event.incidentId(), event.taskId(),
                event.leaseId(), event.oldAuthVersion(), event.newAuthVersion(), event.actor(),
                Timestamp.from(event.occurredAt()), event.detail());
    }

    /**
     * 查询任务的全部资源使用证据，按发生顺序（id 升序）返回。
     */
    public List<LeaseEvent> listEventsByTask(long taskId) {
        return jdbc.query("SELECT * FROM resource_lease_events WHERE task_id = ? ORDER BY id",
                EVENT_MAPPER, taskId);
    }
}
