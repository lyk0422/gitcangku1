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
 * 资源授权租约、证据事件与资源级单行锁的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行的写事务内；涉及同一资源的分配/续期额外持有
 * resource_locks 单行锁（按资源键排序加锁），串行化并发授权，避免重叠授权。
 * 证据事件只追加不改写。
 */
@Repository
public class ResourceLeaseRepository {

    private final JdbcTemplate jdbc;

    public ResourceLeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ResourceLease> LEASE_MAPPER = (rs, n) -> mapLease(rs);

    private static ResourceLease mapLease(ResultSet rs) throws SQLException {
        Timestamp releasedAt = rs.getTimestamp("released_at");
        return new ResourceLease(
                rs.getLong("id"), rs.getLong("task_id"), rs.getLong("incident_id"),
                rs.getString("resource_key"), rs.getInt("auth_version"),
                rs.getString("assigned_by"), rs.getTimestamp("assigned_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                LeaseStatus.valueOf(rs.getString("status")),
                rs.getString("released_by"),
                releasedAt == null ? null : releasedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static final RowMapper<ResourceEvent> EVENT_MAPPER = (rs, n) -> new ResourceEvent(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getLong("task_id"),
            rs.getString("resource_key"), ResourceEventType.valueOf(rs.getString("event_type")),
            rs.getObject("old_version") == null ? null : rs.getInt("old_version"),
            rs.getObject("new_version") == null ? null : rs.getInt("new_version"),
            rs.getString("actor"), rs.getTimestamp("occurred_at").toInstant(),
            rs.getString("detail"), rs.getTimestamp("created_at").toInstant());

    /**
     * 插入 ACTIVE 租约，返回生成主键。
     */
    public long insertLease(ResourceLease lease) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_task_leases (task_id, incident_id, resource_key,"
                            + " auth_version, assigned_by, assigned_at, expires_at, status,"
                            + " released_by, released_at, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, lease.taskId());
            ps.setLong(2, lease.incidentId());
            ps.setString(3, lease.resourceKey());
            ps.setInt(4, lease.authVersion());
            ps.setString(5, lease.assignedBy());
            ps.setTimestamp(6, Timestamp.from(lease.assignedAt()));
            ps.setTimestamp(7, Timestamp.from(lease.expiresAt()));
            ps.setString(8, lease.status().name());
            ps.setString(9, lease.releasedBy());
            ps.setTimestamp(10, lease.releasedAt() == null ? null : Timestamp.from(lease.releasedAt()));
            ps.setTimestamp(11, Timestamp.from(lease.createdAt()));
            ps.setTimestamp(12, Timestamp.from(lease.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询任务当前 ACTIVE 租约（每任务同一时刻至多一条）。
     */
    public Optional<ResourceLease> findActiveByTask(long taskId) {
        List<ResourceLease> rows = jdbc.query(
                "SELECT * FROM incident_task_leases WHERE task_id = ? AND status = 'ACTIVE'"
                        + " ORDER BY id", LEASE_MAPPER, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 查询任务最近一条租约（不限状态），用于明细查询与到期判定。
     */
    public Optional<ResourceLease> findLatestByTask(long taskId) {
        List<ResourceLease> rows = jdbc.query(
                "SELECT * FROM incident_task_leases WHERE task_id = ? ORDER BY id DESC",
                LEASE_MAPPER, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部租约，按创建顺序返回（诊断查询）。
     */
    public List<ResourceLease> listByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_task_leases WHERE incident_id = ? ORDER BY id",
                LEASE_MAPPER, incidentId);
    }

    /**
     * 查询指定资源上仍占用（ACTIVE 且 expires_at 晚于给定时刻）的租约。
     * 调用前必须已持有该资源的 resource_locks 行锁。
     */
    public List<ResourceLease> listOccupying(String resourceKey, Instant now) {
        return jdbc.query("SELECT * FROM incident_task_leases WHERE resource_key = ?"
                        + " AND status = 'ACTIVE' AND expires_at > ? ORDER BY id",
                LEASE_MAPPER, resourceKey, Timestamp.from(now));
    }

    /**
     * 续期：更新授权版本与到期时刻（仅作用 ACTIVE 租约）。
     */
    public void renewLease(long leaseId, int newVersion, Instant newExpiresAt, Instant updatedAt) {
        jdbc.update("UPDATE incident_task_leases SET auth_version = ?, expires_at = ?,"
                        + " updated_at = ? WHERE id = ? AND status = 'ACTIVE'",
                newVersion, Timestamp.from(newExpiresAt), Timestamp.from(updatedAt), leaseId);
    }

    /**
     * 将 ACTIVE 租约置为 EXPIRED（到期判定固化）。
     */
    public void markExpired(long leaseId, Instant updatedAt) {
        jdbc.update("UPDATE incident_task_leases SET status = 'EXPIRED', updated_at = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'",
                Timestamp.from(updatedAt), leaseId);
    }

    /**
     * 将 ACTIVE 租约置为 RELEASED，记录回收人与 UTC 时刻。
     */
    public void markReleased(long leaseId, String actor, Instant at) {
        jdbc.update("UPDATE incident_task_leases SET status = 'RELEASED', released_by = ?,"
                        + " released_at = ?, updated_at = ? WHERE id = ? AND status = 'ACTIVE'",
                actor, Timestamp.from(at), Timestamp.from(at), leaseId);
    }

    /**
     * 追加资源证据事件（只追加不改写），返回生成主键。
     */
    public long insertEvent(ResourceEvent event) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_resource_events (incident_id, task_id, resource_key,"
                            + " event_type, old_version, new_version, actor, occurred_at, detail,"
                            + " created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, event.incidentId());
            ps.setLong(2, event.taskId());
            ps.setString(3, event.resourceKey());
            ps.setString(4, event.eventType().name());
            if (event.oldVersion() == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, event.oldVersion());
            }
            if (event.newVersion() == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setInt(6, event.newVersion());
            }
            ps.setString(7, event.actor());
            ps.setTimestamp(8, Timestamp.from(event.occurredAt()));
            ps.setString(9, event.detail());
            ps.setTimestamp(10, Timestamp.from(event.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询任务的全部资源证据事件，按落库顺序返回。
     */
    public List<ResourceEvent> listEventsByTask(long taskId) {
        return jdbc.query("SELECT * FROM incident_resource_events WHERE task_id = ? ORDER BY id",
                EVENT_MAPPER, taskId);
    }

    /**
     * 持有指定资源的单行锁（resource_locks，SELECT ... FOR UPDATE），
     * 串行化同资源的分配与续期。锁行不存在时先插入；并发首次插入由主键约束串行化。
     * 多资源场景必须由调用方按资源键排序后依次加锁，避免死锁。
     */
    public void lockResource(String resourceKey, Instant now) {
        try {
            jdbc.update("INSERT INTO resource_locks (resource_key, created_at) VALUES (?,?)",
                    resourceKey, Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            // 锁行已存在（含并发事务已提交），继续加锁
        }
        jdbc.queryForObject("SELECT resource_key FROM resource_locks WHERE resource_key = ?"
                + " FOR UPDATE", String.class, resourceKey);
    }
}
