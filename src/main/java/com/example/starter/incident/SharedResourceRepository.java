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
 * 共享资源、资源租约及资源域锁的 JDBC 仓储。
 * 所有容量判定、闭包计算与租约写入均处于先持有 resource_lock 单行锁的写事务内，
 * 配合任务 STARTED 的条件更新，保证容量永不超限且任务不带失效租约启动。
 */
@Repository
public class SharedResourceRepository {

    /** 资源域全局锁行的固定主键。 */
    private static final long RESOURCE_LOCK_ID = 1L;

    private final JdbcTemplate jdbc;

    public SharedResourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<SharedResource> RESOURCE_MAPPER = (rs, n) -> new SharedResource(
            rs.getLong("id"), rs.getString("resource_key"), rs.getString("name"),
            rs.getInt("capacity"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<ResourceLease> LEASE_MAPPER = (rs, n) -> mapLease(rs);

    private static ResourceLease mapLease(ResultSet rs) throws SQLException {
        Timestamp granted = rs.getTimestamp("granted_at");
        Timestamp released = rs.getTimestamp("released_at");
        Timestamp revoked = rs.getTimestamp("revoked_at");
        return new ResourceLease(
                rs.getLong("id"), rs.getString("lease_key"), rs.getLong("resource_id"),
                rs.getLong("task_id"), rs.getLong("incident_id"), rs.getInt("quantity"),
                LeaseStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                granted == null ? null : granted.toInstant(),
                released == null ? null : released.toInstant(),
                revoked == null ? null : revoked.toInstant(),
                rs.getString("revoke_reason"), rs.getString("request_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 插入共享资源，返回生成主键。resourceKey 重复时抛出 DuplicateKeyException。
     */
    public long insert(SharedResource resource) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO shared_resources (resource_key, name, capacity, created_at,"
                            + " updated_at) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, resource.resourceKey());
            ps.setString(2, resource.name());
            ps.setInt(3, resource.capacity());
            ps.setTimestamp(4, Timestamp.from(resource.createdAt()));
            ps.setTimestamp(5, Timestamp.from(resource.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询资源（不加锁），用于只读场景。
     */
    public Optional<SharedResource> findByKey(String resourceKey) {
        List<SharedResource> rows = jdbc.query(
                "SELECT * FROM shared_resources WHERE resource_key = ?", RESOURCE_MAPPER, resourceKey);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询资源（不加锁），用于关联键解析。
     */
    public Optional<SharedResource> findById(long id) {
        List<SharedResource> rows = jdbc.query("SELECT * FROM shared_resources WHERE id = ?",
                RESOURCE_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 列出全部共享资源，按创建顺序返回。
     */
    public List<SharedResource> listAll() {
        return jdbc.query("SELECT * FROM shared_resources ORDER BY id", RESOURCE_MAPPER);
    }

    /**
     * 持有资源域全局锁（单行 SELECT ... FOR UPDATE），串行化申请、抢占与任务开始。
     * 锁行不存在时先插入；并发首次插入由主键约束串行化。
     */
    public void lockResourceDomain() {
        try {
            jdbc.update("INSERT INTO resource_lock (id) VALUES (?)", RESOURCE_LOCK_ID);
        } catch (DuplicateKeyException e) {
            // 锁行已存在（含并发事务已提交），继续加锁
        }
        jdbc.queryForObject("SELECT id FROM resource_lock WHERE id = ? FOR UPDATE",
                Long.class, RESOURCE_LOCK_ID);
    }

    /**
     * 按 id 锁定资源行（SELECT ... FOR UPDATE）。
     */
    public Optional<SharedResource> lockById(long id) {
        List<SharedResource> rows = jdbc.query(
                "SELECT * FROM shared_resources WHERE id = ? FOR UPDATE", RESOURCE_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 插入 ACTIVE 租约（授予时即生效），version 固定为 1，返回生成主键。
     * leaseKey 重复、同任务同资源已有 ACTIVE 租约时由唯一约束拒绝。
     */
    public long insertActiveLease(ResourceLease lease) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO resource_leases (lease_key, resource_id, task_id, incident_id,"
                            + " quantity, status, version, granted_at, released_at, revoked_at,"
                            + " revoke_reason, request_id, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,'ACTIVE',1,?,NULL,NULL,NULL,?,?,?)",
                    new String[] {"id"});
            ps.setString(1, lease.leaseKey());
            ps.setLong(2, lease.resourceId());
            ps.setLong(3, lease.taskId());
            ps.setLong(4, lease.incidentId());
            ps.setInt(5, lease.quantity());
            ps.setTimestamp(6, Timestamp.from(lease.grantedAt()));
            ps.setString(7, lease.requestId());
            ps.setTimestamp(8, Timestamp.from(lease.createdAt()));
            ps.setTimestamp(9, Timestamp.from(lease.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键锁定租约行（SELECT ... FOR UPDATE），用于抢占前的版本复核。
     */
    public Optional<ResourceLease> lockLeaseByKey(String leaseKey) {
        List<ResourceLease> rows = jdbc.query(
                "SELECT * FROM resource_leases WHERE lease_key = ? FOR UPDATE", LEASE_MAPPER, leaseKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询租约（不加锁）。
     */
    public Optional<ResourceLease> findLeaseByKey(String leaseKey) {
        List<ResourceLease> rows = jdbc.query(
                "SELECT * FROM resource_leases WHERE lease_key = ?", LEASE_MAPPER, leaseKey);
        return rows.stream().findFirst();
    }

    /**
     * 条件撤销：仅当租约仍 ACTIVE 且版本匹配时置为 REVOKED 并递增版本。
     * 返回更新行数；0 表示版本过期或租约已终态，调用方据此整单 409。
     */
    public int revokeIfVersion(long leaseId, long expectedVersion, String reason,
                               String requestId, Instant at) {
        return jdbc.update("UPDATE resource_leases SET status = 'REVOKED', version = version + 1,"
                        + " revoked_at = ?, revoke_reason = ?, request_id = ?, updated_at = ?"
                        + " WHERE id = ? AND status = 'ACTIVE' AND version = ?",
                Timestamp.from(at), reason, requestId, Timestamp.from(at), leaseId, expectedVersion);
    }

    /**
     * 条件释放：仅当租约仍 ACTIVE 时置为 RELEASED 并递增版本。
     * 任务完成/取消与撤销并发时，未撤销的租约才释放；返回更新行数。
     */
    public int releaseIfActive(long leaseId, Instant at) {
        return jdbc.update("UPDATE resource_leases SET status = 'RELEASED', version = version + 1,"
                        + " released_at = ?, updated_at = ? WHERE id = ? AND status = 'ACTIVE'",
                Timestamp.from(at), Timestamp.from(at), leaseId);
    }

    /**
     * 查询资源全部 ACTIVE 租约（容量判定用），按 id 返回。调用前须持有资源域锁。
     */
    public List<ResourceLease> listActiveForResource(long resourceId) {
        return jdbc.query(
                "SELECT * FROM resource_leases WHERE resource_id = ? AND status = 'ACTIVE' ORDER BY id",
                LEASE_MAPPER, resourceId);
    }

    /**
     * 查询任务全部租约（任意状态），按 id 返回。
     */
    public List<ResourceLease> listForTask(long taskId) {
        return jdbc.query("SELECT * FROM resource_leases WHERE task_id = ? ORDER BY id",
                LEASE_MAPPER, taskId);
    }

    /**
     * 查询任务全部 ACTIVE 租约（任务开始门禁用），按 id 返回。
     */
    public List<ResourceLease> listActiveForTask(long taskId) {
        return jdbc.query(
                "SELECT * FROM resource_leases WHERE task_id = ? AND status = 'ACTIVE' ORDER BY id",
                LEASE_MAPPER, taskId);
    }

    /**
     * 查询事件全部租约（任意状态），按 id 返回。
     */
    public List<ResourceLease> listForIncident(long incidentId) {
        return jdbc.query("SELECT * FROM resource_leases WHERE incident_id = ? ORDER BY id",
                LEASE_MAPPER, incidentId);
    }

    /**
     * 查询资源全部租约（任意状态，历史查询用），按 id 返回。
     */
    public List<ResourceLease> listForResource(long resourceId) {
        return jdbc.query("SELECT * FROM resource_leases WHERE resource_id = ? ORDER BY id",
                LEASE_MAPPER, resourceId);
    }

    /**
     * 查询任务在指定资源上的 ACTIVE 租约（同任务同资源至多一条）。
     */
    public Optional<ResourceLease> findActive(long resourceId, long taskId) {
        List<ResourceLease> rows = jdbc.query(
                "SELECT * FROM resource_leases WHERE resource_id = ? AND task_id = ?"
                        + " AND status = 'ACTIVE'", LEASE_MAPPER, resourceId, taskId);
        return rows.stream().findFirst();
    }

    /** 租约行 + 关联业务键（资源键、事件键、任务键），用于组装只读视图。 */
    public record LeaseRow(ResourceLease lease, String resourceKey, String incidentKey,
                           String taskKey) {
    }

    private static final class LeaseRowMapper implements RowMapper<LeaseRow> {
        private final String leaseAlias;

        private LeaseRowMapper(String leaseAlias) {
            this.leaseAlias = leaseAlias;
        }

        @Override
        public LeaseRow mapRow(ResultSet rs, int n) throws SQLException {
            ResourceLease lease = mapLease(rs);
            return new LeaseRow(lease, rs.getString("res_key"),
                    rs.getString("inc_key"), rs.getString("tsk_key"));
        }
    }

    private static final String LEASE_JOIN_SELECT = "SELECT l.*, r.resource_key AS res_key,"
            + " i.incident_key AS inc_key, t.task_key AS tsk_key"
            + " FROM resource_leases l"
            + " JOIN shared_resources r ON r.id = l.resource_id"
            + " JOIN incidents i ON i.id = l.incident_id"
            + " JOIN incident_tasks t ON t.id = l.task_id";

    /** 按业务键查询租约行（含关联业务键，不加锁）。 */
    public Optional<LeaseRow> findLeaseRowByKey(String leaseKey) {
        List<LeaseRow> rows = jdbc.query(LEASE_JOIN_SELECT + " WHERE l.lease_key = ?",
                new LeaseRowMapper("l"), leaseKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键锁定租约行（仅对 resource_leases 单表 FOR UPDATE），随后应用层用非锁读
     * 解析资源/事件/任务业务键。刻意避免多表 JOIN ... FOR UPDATE，以免与"先锁事件行、
     * 再取资源域锁"的申请/开始路径形成相反加锁顺序而死锁；归属列不可变，非锁读安全。
     */
    public Optional<ResourceLease> lockLeaseByKeyForUpdate(String leaseKey) {
        List<ResourceLease> rows = jdbc.query(
                "SELECT * FROM resource_leases WHERE lease_key = ? FOR UPDATE", LEASE_MAPPER, leaseKey);
        return rows.stream().findFirst();
    }

    /** 查询资源全部租约行（任意状态，历史查询用），按租约 id 返回。 */
    public List<LeaseRow> listLeaseRowsForResource(long resourceId) {
        return jdbc.query(LEASE_JOIN_SELECT + " WHERE l.resource_id = ? ORDER BY l.id",
                new LeaseRowMapper("l"), resourceId);
    }

    /** 查询资源当前 ACTIVE 租约行（含业务键，占用明细查询用），按租约 id 返回。 */
    public List<LeaseRow> listActiveRowsForResource(long resourceId) {
        return jdbc.query(LEASE_JOIN_SELECT + " WHERE l.resource_id = ? AND l.status = 'ACTIVE'"
                        + " ORDER BY l.id", new LeaseRowMapper("l"), resourceId);
    }

    /** 查询事件全部租约行（任意状态），按租约 id 返回。 */
    public List<LeaseRow> listLeaseRowsForIncident(long incidentId) {
        return jdbc.query(LEASE_JOIN_SELECT + " WHERE l.incident_id = ? ORDER BY l.id",
                new LeaseRowMapper("l"), incidentId);
    }
}
