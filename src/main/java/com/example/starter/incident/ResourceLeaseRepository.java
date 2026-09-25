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
 * 资源租约与资质风险记录的 JDBC 仓储。
 * 租约分配/替换/撤销均先持有 lease_lock 单行全局锁，使资源租约冲突检测、
 * 资质后态校验与风险裁决按事务提交顺序串行。current_flag=1 为任务当前租约，
 * (task_id,1) 唯一约束兜底每任务至多一条当前租约。credential_risks 只追加。
 */
@Repository
public class ResourceLeaseRepository {

    /** 租约裁决全局锁行的固定主键。 */
    private static final long LEASE_LOCK_ID = 1L;

    private final JdbcTemplate jdbc;

    public ResourceLeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ResourceLease> LEASE_MAPPER = (rs, n) -> mapLease(rs);

    private static final RowMapper<CredentialRisk> RISK_MAPPER = (rs, n) -> new CredentialRisk(
            rs.getLong("id"), rs.getLong("lease_id"), rs.getLong("task_id"),
            rs.getLong("incident_id"), rs.getString("resource_id"),
            rs.getString("credential_code"), rs.getString("reason"),
            rs.getString("triggered_by"), rs.getTimestamp("triggered_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private static ResourceLease mapLease(ResultSet rs) throws SQLException {
        Timestamp replacedAt = rs.getTimestamp("replaced_at");
        Long currentFlag = rs.getObject("current_flag", Long.class);
        return new ResourceLease(
                rs.getLong("id"), rs.getLong("task_id"), rs.getString("resource_id"),
                rs.getLong("resource_version"),
                rs.getTimestamp("lease_start").toInstant(),
                rs.getTimestamp("lease_end").toInstant(),
                CredentialCodec.decode(rs.getString("required_credentials")),
                LeaseStatus.valueOf(rs.getString("status")), currentFlag,
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                rs.getString("replaced_by"),
                replacedAt == null ? null : replacedAt.toInstant());
    }

    /**
     * 持有租约裁决全局锁（单行 SELECT ... FOR UPDATE），串行化分配/替换/撤销。
     * 锁行不存在时先插入；并发首次插入由主键约束串行化。
     */
    public void lockLeases() {
        try {
            jdbc.update("INSERT INTO lease_lock (id) VALUES (?)", LEASE_LOCK_ID);
        } catch (DuplicateKeyException e) {
            // 锁行已存在（含并发事务已提交），继续加锁
        }
        jdbc.queryForObject("SELECT id FROM lease_lock WHERE id = ? FOR UPDATE",
                Long.class, LEASE_LOCK_ID);
    }

    /**
     * 插入一条当前生效租约，返回生成主键。(task_id, current_flag=1) 唯一约束兜底。
     */
    public long insert(ResourceLease lease) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO resource_leases (task_id, resource_id, resource_version,"
                            + " lease_start, lease_end, required_credentials, status, current_flag,"
                            + " created_by, created_at, replaced_by, replaced_at)"
                            + " VALUES (?,?,?,?,?,?,?,1,?,?,NULL,NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, lease.taskId());
            ps.setString(2, lease.resourceId());
            ps.setLong(3, lease.resourceVersion());
            ps.setTimestamp(4, Timestamp.from(lease.leaseStart()));
            ps.setTimestamp(5, Timestamp.from(lease.leaseEnd()));
            ps.setString(6, CredentialCodec.encode(lease.requiredCredentials()));
            ps.setString(7, lease.status().name());
            ps.setString(8, lease.createdBy());
            ps.setTimestamp(9, Timestamp.from(lease.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按主键查询租约。
     */
    public Optional<ResourceLease> findById(long leaseId) {
        List<ResourceLease> rows = jdbc.query("SELECT * FROM resource_leases WHERE id = ?",
                LEASE_MAPPER, leaseId);
        return rows.stream().findFirst();
    }

    /**
     * 查询任务当前租约（current_flag=1）。
     */
    public Optional<ResourceLease> findCurrentByTask(long taskId) {
        List<ResourceLease> rows = jdbc.query(
                "SELECT * FROM resource_leases WHERE task_id = ? AND current_flag = 1",
                LEASE_MAPPER, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 锁定读任务当前租约，用于替换等写路径。
     */
    public Optional<ResourceLease> lockCurrentByTask(long taskId) {
        List<ResourceLease> rows = jdbc.query(
                "SELECT * FROM resource_leases WHERE task_id = ? AND current_flag = 1 FOR UPDATE",
                LEASE_MAPPER, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 将当前租约标记为已替换并解除当前标记（current_flag 置 NULL）。
     */
    public void markReplaced(long leaseId, String replacedBy, Instant at) {
        jdbc.update("UPDATE resource_leases SET status = 'REPLACED', current_flag = NULL,"
                        + " replaced_by = ?, replaced_at = ? WHERE id = ?",
                replacedBy, Timestamp.from(at), leaseId);
    }

    /**
     * 查询资源在指定时刻仍有效的当前租约（lease_end 严格晚于 referenceAt）。
     * 撤销资质时据此找出受影响的高危租约。
     */
    public List<ResourceLease> listCurrentByResourceValidAfter(String resourceId,
                                                                Instant referenceAt) {
        return jdbc.query("SELECT * FROM resource_leases WHERE resource_id = ?"
                        + " AND current_flag = 1 AND status = 'ACTIVE' AND lease_end > ?"
                        + " ORDER BY id",
                LEASE_MAPPER, resourceId, Timestamp.from(referenceAt));
    }

    /**
     * 查询资源与 [start,end) 时段重叠的当前生效租约，用于租约冲突检测。
     * 相邻时段（一端结束恰为另一端开始）不视为冲突。
     */
    public List<ResourceLease> listCurrentOverlapping(String resourceId, Instant start, Instant end) {
        return jdbc.query("SELECT * FROM resource_leases WHERE resource_id = ?"
                        + " AND current_flag = 1 AND status = 'ACTIVE'"
                        + " AND lease_start < ? AND ? < lease_end ORDER BY id",
                LEASE_MAPPER, resourceId, Timestamp.from(end), Timestamp.from(start));
    }

    /**
     * 追加一条不可变资质风险记录，返回生成主键。
     */
    public long insertRisk(CredentialRisk risk) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO credential_risks (lease_id, task_id, incident_id, resource_id,"
                            + " credential_code, reason, triggered_by, triggered_at, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, risk.leaseId());
            ps.setLong(2, risk.taskId());
            ps.setLong(3, risk.incidentId());
            ps.setString(4, risk.resourceId());
            ps.setString(5, risk.credentialCode());
            ps.setString(6, risk.reason());
            ps.setString(7, risk.triggeredBy());
            ps.setTimestamp(8, Timestamp.from(risk.triggeredAt()));
            ps.setTimestamp(9, Timestamp.from(risk.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询事件全部资质风险记录，按写入顺序返回。
     */
    public List<CredentialRisk> listRisksByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM credential_risks WHERE incident_id = ? ORDER BY id",
                RISK_MAPPER, incidentId);
    }

    /**
     * 查询单任务全部资质风险记录，按写入顺序返回。
     */
    public List<CredentialRisk> listRisksByTask(long taskId) {
        return jdbc.query("SELECT * FROM credential_risks WHERE task_id = ? ORDER BY id",
                RISK_MAPPER, taskId);
    }
}
