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
 * 共享资源租约的 JDBC 仓储。
 * 所有写路径均处于先持有资源池全局锁的写事务内；
 * lease_key 唯一约束兜底并发重复插入。
 */
@Repository
public class ResourceLeaseRepository {

    private final JdbcTemplate jdbc;

    public ResourceLeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 租约关联视图行：租约本体 + 资源/事件/任务业务键，用于只读查询组装。
     */
    public record LeaseDetail(ResourceLease lease, String resourceKey, String incidentKey,
                              String taskKey) {
    }

    private static final RowMapper<ResourceLease> MAPPER = (rs, n) -> mapLease(rs);

    private static final RowMapper<LeaseDetail> DETAIL_MAPPER = (rs, n) -> new LeaseDetail(
            mapLease(rs), rs.getString("resource_key"), rs.getString("incident_key"),
            rs.getString("task_key"));

    private static ResourceLease mapLease(ResultSet rs) throws SQLException {
        Timestamp releasedAt = rs.getTimestamp("released_at");
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new ResourceLease(
                rs.getLong("id"), rs.getString("lease_key"), rs.getLong("resource_id"),
                rs.getLong("incident_id"), rs.getLong("task_id"), rs.getInt("units"),
                LeaseStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                rs.getString("request_id"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                releasedAt == null ? null : releasedAt.toInstant(),
                revokedAt == null ? null : revokedAt.toInstant());
    }

    /**
     * 插入 ACTIVE 租约，返回生成主键。lease_key 唯一约束兜底并发重复插入。
     */
    public long insert(ResourceLease lease) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO resource_leases (lease_key, resource_id, incident_id, task_id,"
                            + " units, status, version, request_id, created_by, created_at,"
                            + " updated_at, released_at, revoked_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, lease.leaseKey());
            ps.setLong(2, lease.resourceId());
            ps.setLong(3, lease.incidentId());
            ps.setLong(4, lease.taskId());
            ps.setInt(5, lease.units());
            ps.setString(6, lease.status().name());
            ps.setLong(7, lease.version());
            ps.setString(8, lease.requestId());
            ps.setString(9, lease.createdBy());
            ps.setTimestamp(10, Timestamp.from(lease.createdAt()));
            ps.setTimestamp(11, Timestamp.from(lease.updatedAt()));
            ps.setTimestamp(12, lease.releasedAt() == null ? null : Timestamp.from(lease.releasedAt()));
            ps.setTimestamp(13, lease.revokedAt() == null ? null : Timestamp.from(lease.revokedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按 leaseKey 查询租约（不加锁），用于幂等比对与明细查询。
     */
    public Optional<ResourceLease> findByKey(String leaseKey) {
        List<ResourceLease> rows = jdbc.query(
                "SELECT * FROM resource_leases WHERE lease_key = ?", MAPPER, leaseKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询任务在指定资源上的 ACTIVE 租约（同任务同资源至多一条）。
     */
    public Optional<ResourceLease> findActiveByTaskAndResource(long taskId, long resourceId) {
        List<ResourceLease> rows = jdbc.query(
                "SELECT * FROM resource_leases WHERE task_id = ? AND resource_id = ?"
                        + " AND status = 'ACTIVE'",
                MAPPER, taskId, resourceId);
        return rows.stream().findFirst();
    }

    /**
     * 查询任务的全部 ACTIVE 租约（启动门禁与释放用）。
     */
    public List<ResourceLease> listActiveByTask(long taskId) {
        return jdbc.query("SELECT * FROM resource_leases WHERE task_id = ? AND status = 'ACTIVE'"
                + " ORDER BY id", MAPPER, taskId);
    }

    /**
     * 查询资源上全部 ACTIVE 租约，按创建顺序返回。
     */
    public List<ResourceLease> listActiveByResource(long resourceId) {
        return jdbc.query("SELECT * FROM resource_leases WHERE resource_id = ?"
                + " AND status = 'ACTIVE' ORDER BY id", MAPPER, resourceId);
    }

    /**
     * 资源上 ACTIVE 租约的单位合计（容量核算用）。
     */
    public int sumActiveUnits(long resourceId) {
        Integer sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(units), 0) FROM resource_leases WHERE resource_id = ?"
                        + " AND status = 'ACTIVE'",
                Integer.class, resourceId);
        return sum == null ? 0 : sum;
    }

    /**
     * 将任务的全部 ACTIVE 租约原子置为 RELEASED（任务完成/取消时同事务调用），
     * 版本加 1 并记录释放 UTC 时刻，返回释放条数。
     */
    public int releaseActiveForTask(long taskId, Instant at) {
        return jdbc.update("UPDATE resource_leases SET status = 'RELEASED', version = version + 1,"
                        + " released_at = ?, updated_at = ? WHERE task_id = ? AND status = 'ACTIVE'",
                Timestamp.from(at), Timestamp.from(at), taskId);
    }

    /**
     * 将指定 ACTIVE 租约原子置为 REVOKED（抢占计划执行时同事务调用），
     * 版本加 1 并记录撤销 UTC 时刻；已非 ACTIVE 时返回 0。
     */
    public int revoke(long leaseId, Instant at) {
        return jdbc.update("UPDATE resource_leases SET status = 'REVOKED', version = version + 1,"
                        + " revoked_at = ?, updated_at = ? WHERE id = ? AND status = 'ACTIVE'",
                Timestamp.from(at), Timestamp.from(at), leaseId);
    }

    /**
     * 查询资源上全部租约的关联视图（含资源/事件/任务业务键），按创建顺序返回。
     */
    public List<LeaseDetail> listDetailsByResource(long resourceId) {
        return jdbc.query("SELECT l.*, r.resource_key, i.incident_key, t.task_key"
                        + " FROM resource_leases l"
                        + " JOIN shared_resources r ON r.id = l.resource_id"
                        + " JOIN incidents i ON i.id = l.incident_id"
                        + " JOIN incident_tasks t ON t.id = l.task_id"
                        + " WHERE l.resource_id = ? ORDER BY l.id",
                DETAIL_MAPPER, resourceId);
    }

    /**
     * 按租约 id 集合查询关联视图（含资源/事件/任务业务键），按创建顺序返回。
     */
    public List<LeaseDetail> listDetailsByIds(List<Long> leaseIds) {
        if (leaseIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", leaseIds.stream().map(id -> "?").toList());
        return jdbc.query("SELECT l.*, r.resource_key, i.incident_key, t.task_key"
                        + " FROM resource_leases l"
                        + " JOIN shared_resources r ON r.id = l.resource_id"
                        + " JOIN incidents i ON i.id = l.incident_id"
                        + " JOIN incident_tasks t ON t.id = l.task_id"
                        + " WHERE l.id IN (" + placeholders + ") ORDER BY l.id",
                DETAIL_MAPPER, leaseIds.toArray());
    }
}
