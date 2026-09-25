package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
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
 * 租约分配、替换与资质撤销均先持有 lease_domain_lock 单行锁，
 * 使租约冲突校验、资质后态校验与写入相对彼此串行化，按事务提交顺序裁决。
 * 风险记录只插入不更新；(lease_id, credential_code) 唯一兜底重复写入。
 */
@Repository
public class LeaseRepository {

    /** 租约域全局锁行的固定主键。 */
    private static final long DOMAIN_LOCK_ID = 1L;

    private final JdbcTemplate jdbc;

    public LeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ResourceLease> LEASE_MAPPER = (rs, n) -> mapLease(rs);
    private static final RowMapper<CredentialRiskRecord> RISK_MAPPER = (rs, n) -> new CredentialRiskRecord(
            rs.getLong("id"), rs.getLong("lease_id"), rs.getLong("task_id"),
            rs.getLong("resource_id"), rs.getString("credential_code"),
            rs.getTimestamp("revoked_at").toInstant(),
            rs.getTimestamp("detected_at").toInstant());

    private static ResourceLease mapLease(ResultSet rs) throws SQLException {
        String codes = rs.getString("credential_codes");
        return new ResourceLease(
                rs.getLong("id"), rs.getString("lease_key"), rs.getLong("resource_id"),
                rs.getInt("resource_version"), rs.getLong("task_id"),
                codes == null || codes.isEmpty() ? List.of() : Arrays.asList(codes.split(",")),
                rs.getTimestamp("lease_start").toInstant(),
                rs.getTimestamp("lease_end").toInstant(),
                LeaseStatus.valueOf(rs.getString("status")),
                rs.getString("replaced_by"), rs.getString("operator"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 持有租约域全局锁（单行 SELECT ... FOR UPDATE），串行化租约写路径。
     * 锁行不存在时先插入；并发首次插入由主键约束串行化。
     */
    public void lockDomain() {
        try {
            jdbc.update("INSERT INTO lease_domain_lock (id) VALUES (?)", DOMAIN_LOCK_ID);
        } catch (DuplicateKeyException e) {
            // 锁行已存在（含并发事务已提交），继续加锁
        }
        jdbc.queryForObject("SELECT id FROM lease_domain_lock WHERE id = ? FOR UPDATE",
                Long.class, DOMAIN_LOCK_ID);
    }

    /**
     * 插入租约，返回生成主键。(lease_key, task_id) 唯一约束兜底并发重复写入。
     */
    public long insert(ResourceLease lease) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO resource_leases (lease_key, resource_id, resource_version, task_id,"
                            + " credential_codes, lease_start, lease_end, status, replaced_by,"
                            + " operator, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, lease.leaseKey());
            ps.setLong(2, lease.resourceId());
            ps.setInt(3, lease.resourceVersion());
            ps.setLong(4, lease.taskId());
            ps.setString(5, String.join(",", lease.credentialCodes()));
            ps.setTimestamp(6, Timestamp.from(lease.leaseStart()));
            ps.setTimestamp(7, Timestamp.from(lease.leaseEnd()));
            ps.setString(8, lease.status().name());
            ps.setString(9, lease.replacedBy());
            ps.setString(10, lease.operator());
            ps.setTimestamp(11, Timestamp.from(lease.createdAt()));
            ps.setTimestamp(12, Timestamp.from(lease.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询任务当前持有资源的租约（ACTIVE 或 CREDENTIAL_RISK），至多一条。
     */
    public Optional<ResourceLease> findHoldingByTask(long taskId) {
        List<ResourceLease> rows = jdbc.query(
                "SELECT * FROM resource_leases WHERE task_id = ?"
                        + " AND status IN ('ACTIVE','CREDENTIAL_RISK') ORDER BY id",
                LEASE_MAPPER, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 查询资源仍持有中的租约（ACTIVE 或 CREDENTIAL_RISK），按创建顺序返回。
     */
    public List<ResourceLease> listHoldingByResource(long resourceId) {
        return jdbc.query("SELECT * FROM resource_leases WHERE resource_id = ?"
                + " AND status IN ('ACTIVE','CREDENTIAL_RISK') ORDER BY id", LEASE_MAPPER, resourceId);
    }

    /**
     * 查询资源在指定时段内冲突的持有中租约：时段半开区间相交即冲突。
     */
    public List<ResourceLease> listConflicting(long resourceId, Instant leaseStart, Instant leaseEnd) {
        return jdbc.query("SELECT * FROM resource_leases WHERE resource_id = ?"
                + " AND status IN ('ACTIVE','CREDENTIAL_RISK')"
                + " AND lease_start < ? AND lease_end > ? ORDER BY id",
                LEASE_MAPPER, resourceId, Timestamp.from(leaseEnd), Timestamp.from(leaseStart));
    }

    /**
     * 查询资源处于 CREDENTIAL_RISK 的租约，按创建顺序返回。
     */
    public List<ResourceLease> listRiskByResource(long resourceId) {
        return jdbc.query("SELECT * FROM resource_leases WHERE resource_id = ?"
                + " AND status = 'CREDENTIAL_RISK' ORDER BY id", LEASE_MAPPER, resourceId);
    }

    /**
     * 查询事件下处于 CREDENTIAL_RISK 的租约，按创建顺序返回。
     */
    public List<ResourceLease> listRiskByIncident(long incidentId) {
        return jdbc.query("SELECT l.* FROM resource_leases l"
                + " JOIN incident_tasks t ON t.id = l.task_id"
                + " WHERE t.incident_id = ? AND l.status = 'CREDENTIAL_RISK' ORDER BY l.id",
                LEASE_MAPPER, incidentId);
    }

    /**
     * 查询资源仍持有中且租约时段在未来仍有效的租约（lease_end 晚于指定时刻）。
     */
    public List<ResourceLease> listFutureHoldingByResource(long resourceId, Instant now) {
        return jdbc.query("SELECT * FROM resource_leases WHERE resource_id = ?"
                + " AND status = 'ACTIVE' AND lease_end > ? ORDER BY id",
                LEASE_MAPPER, resourceId, Timestamp.from(now));
    }

    /**
     * 更新租约状态（可附带 replaced_by），记录变更 UTC 时刻。
     */
    public void updateStatus(long leaseId, LeaseStatus status, String replacedBy, Instant updatedAt) {
        jdbc.update("UPDATE resource_leases SET status = ?, replaced_by = ?, updated_at = ?"
                        + " WHERE id = ?",
                status.name(), replacedBy, Timestamp.from(updatedAt), leaseId);
    }

    /**
     * 将任务持有中的租约条件释放（任务进入终态时调用）；
     * 仅 ACTIVE/CREDENTIAL_RISK 行被更新，返回更新行数。
     */
    public int releaseHoldingByTask(long taskId, Instant updatedAt) {
        return jdbc.update("UPDATE resource_leases SET status = 'RELEASED', updated_at = ?"
                        + " WHERE task_id = ? AND status IN ('ACTIVE','CREDENTIAL_RISK')",
                Timestamp.from(updatedAt), taskId);
    }

    /**
     * 写入一条不可变资质风险记录。(lease_id, credential_code) 唯一兜底重复写入。
     */
    public long insertRiskRecord(CredentialRiskRecord record) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO credential_risk_records (lease_id, task_id, resource_id,"
                            + " credential_code, revoked_at, detected_at) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, record.leaseId());
            ps.setLong(2, record.taskId());
            ps.setLong(3, record.resourceId());
            ps.setString(4, record.credentialCode());
            ps.setTimestamp(5, Timestamp.from(record.revokedAt()));
            ps.setTimestamp(6, Timestamp.from(record.detectedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询租约关联的全部风险记录，按落库顺序返回。
     */
    public List<CredentialRiskRecord> listRiskRecordsByLease(long leaseId) {
        return jdbc.query("SELECT * FROM credential_risk_records WHERE lease_id = ? ORDER BY id",
                RISK_MAPPER, leaseId);
    }

    /**
     * 查询多条租约关联的全部风险记录，按落库顺序返回。
     */
    public List<CredentialRiskRecord> listRiskRecordsByLeases(List<Long> leaseIds) {
        if (leaseIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", leaseIds.stream().map(id -> "?").toList());
        return jdbc.query("SELECT * FROM credential_risk_records WHERE lease_id IN ("
                + placeholders + ") ORDER BY id", RISK_MAPPER, leaseIds.toArray());
    }
}
