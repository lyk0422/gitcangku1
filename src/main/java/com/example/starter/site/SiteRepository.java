package com.example.starter.site;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 现场隔离与作业许可的 JDBC 持久化。时间统一以 UTC 纪元毫秒（BIGINT）存取。
 */
@Repository
public class SiteRepository {

    private final JdbcTemplate jdbc;

    public SiteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 隔离记录行。removedAt 为 null 表示未拆除。
     */
    public record IsolationRow(String isolationKey, String deviceId, long plannedStartUtc,
                               long plannedEndUtc, String lockedBy, IsolationStatus status,
                               long createdAt, Long removedAt) {
    }

    /**
     * 作业许可行。closedAt 为 null 表示未关闭。
     */
    public record PermitRow(String permitKey, String crewName, long workStartUtc, long workEndUtc,
                            String applicant, PermitStatus status, long createdAt, Long closedAt) {
    }

    /**
     * 批准明细行。
     */
    public record ApprovalRow(String permitKey, String approver, int seqNo, long approvedAt) {
    }

    /**
     * 幂等命令记录行。
     */
    public record CommandRow(String commandKey, String operation, String fingerprint,
                             int httpStatus, String responseBody, long createdAt) {
    }

    private static final RowMapper<IsolationRow> ISOLATION_MAPPER = (rs, n) -> new IsolationRow(
            rs.getString("isolation_key"), rs.getString("device_id"),
            rs.getLong("planned_start_utc"), rs.getLong("planned_end_utc"),
            rs.getString("locked_by"), IsolationStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at"), nullableLong(rs, "removed_at"));

    private static final RowMapper<PermitRow> PERMIT_MAPPER = (rs, n) -> new PermitRow(
            rs.getString("permit_key"), rs.getString("crew_name"),
            rs.getLong("work_start_utc"), rs.getLong("work_end_utc"),
            rs.getString("applicant"), PermitStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at"), nullableLong(rs, "closed_at"));

    private static final RowMapper<ApprovalRow> APPROVAL_MAPPER = (rs, n) -> new ApprovalRow(
            rs.getString("permit_key"), rs.getString("approver"),
            rs.getInt("seq_no"), rs.getLong("approved_at"));

    private static final RowMapper<CommandRow> COMMAND_MAPPER = (rs, n) -> new CommandRow(
            rs.getString("command_key"), rs.getString("operation"), rs.getString("fingerprint"),
            rs.getInt("http_status"), rs.getString("response_body"), rs.getLong("created_at"));

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    // ---------- 隔离记录 ----------

    public Optional<IsolationRow> findIsolation(String isolationKey) {
        return jdbc.query("SELECT * FROM isolation_record WHERE isolation_key = ?",
                ISOLATION_MAPPER, isolationKey).stream().findFirst();
    }

    public void insertIsolation(IsolationRow row) {
        jdbc.update("INSERT INTO isolation_record"
                        + " (isolation_key, device_id, planned_start_utc, planned_end_utc, locked_by, status, created_at, removed_at)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                row.isolationKey(), row.deviceId(), row.plannedStartUtc(), row.plannedEndUtc(),
                row.lockedBy(), row.status().name(), row.createdAt(), row.removedAt());
    }

    /**
     * 查询同一设备下与给定区间重叠的已安装隔离（左闭右开，端点相接不算重叠）。
     */
    public List<IsolationRow> findInstalledOverlapping(String deviceId, long startUtc, long endUtc) {
        return jdbc.query("SELECT * FROM isolation_record"
                        + " WHERE device_id = ? AND status = 'INSTALLED'"
                        + " AND planned_start_utc < ? AND planned_end_utc > ?",
                ISOLATION_MAPPER, deviceId, endUtc, startUtc);
    }

    public List<IsolationRow> findIsolationsByKeys(List<String> keys) {
        String placeholders = String.join(",", keys.stream().map(k -> "?").toList());
        return jdbc.query("SELECT * FROM isolation_record WHERE isolation_key IN (" + placeholders + ")",
                ISOLATION_MAPPER, keys.toArray());
    }

    public void markIsolationRemoved(String isolationKey, long removedAt) {
        jdbc.update("UPDATE isolation_record SET status = 'REMOVED', removed_at = ? WHERE isolation_key = ?",
                removedAt, isolationKey);
    }

    public List<IsolationRow> listIsolations(String deviceId, IsolationStatus status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM isolation_record WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (deviceId != null) {
            sql.append(" AND device_id = ?");
            args.add(deviceId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        sql.append(" ORDER BY created_at, isolation_key");
        return jdbc.query(sql.toString(), ISOLATION_MAPPER, args.toArray());
    }

    // ---------- 作业许可 ----------

    public Optional<PermitRow> findPermit(String permitKey) {
        return jdbc.query("SELECT * FROM permit WHERE permit_key = ?",
                PERMIT_MAPPER, permitKey).stream().findFirst();
    }

    public void insertPermit(PermitRow row) {
        jdbc.update("INSERT INTO permit"
                        + " (permit_key, crew_name, work_start_utc, work_end_utc, applicant, status, created_at, closed_at)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                row.permitKey(), row.crewName(), row.workStartUtc(), row.workEndUtc(),
                row.applicant(), row.status().name(), row.createdAt(), row.closedAt());
    }

    public void updatePermitStatus(String permitKey, PermitStatus status, Long closedAt) {
        jdbc.update("UPDATE permit SET status = ?, closed_at = ? WHERE permit_key = ?",
                status.name(), closedAt, permitKey);
    }

    public List<PermitRow> listPermits(PermitStatus status) {
        if (status == null) {
            return jdbc.query("SELECT * FROM permit ORDER BY created_at, permit_key", PERMIT_MAPPER);
        }
        return jdbc.query("SELECT * FROM permit WHERE status = ? ORDER BY created_at, permit_key",
                PERMIT_MAPPER, status.name());
    }

    public void insertPermitIsolation(String permitKey, String isolationKey) {
        jdbc.update("INSERT INTO permit_isolation (permit_key, isolation_key) VALUES (?,?)",
                permitKey, isolationKey);
    }

    public List<String> findPermitIsolationKeys(String permitKey) {
        return jdbc.queryForList("SELECT isolation_key FROM permit_isolation WHERE permit_key = ? ORDER BY isolation_key",
                String.class, permitKey);
    }

    /**
     * 统计引用指定隔离的生效许可数量。
     */
    public int countEffectivePermitsReferencing(String isolationKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM permit_isolation pi JOIN permit p ON p.permit_key = pi.permit_key"
                        + " WHERE pi.isolation_key = ? AND p.status = 'EFFECTIVE'",
                Integer.class, isolationKey);
        return count == null ? 0 : count;
    }

    /**
     * 查询引用指定隔离、且作业区间与给定区间重叠的其他生效许可。
     */
    public List<PermitRow> findEffectivePermitsOccupying(String isolationKey, long startUtc, long endUtc,
                                                         String excludePermitKey) {
        return jdbc.query("SELECT p.* FROM permit_isolation pi JOIN permit p ON p.permit_key = pi.permit_key"
                        + " WHERE pi.isolation_key = ? AND p.status = 'EFFECTIVE'"
                        + " AND p.work_start_utc < ? AND p.work_end_utc > ? AND p.permit_key <> ?",
                PERMIT_MAPPER, isolationKey, endUtc, startUtc, excludePermitKey);
    }

    // ---------- 批准明细 ----------

    public void insertApproval(ApprovalRow row) {
        jdbc.update("INSERT INTO permit_approval (permit_key, approver, seq_no, approved_at) VALUES (?,?,?,?)",
                row.permitKey(), row.approver(), row.seqNo(), row.approvedAt());
    }

    public List<ApprovalRow> listApprovals(String permitKey) {
        return jdbc.query("SELECT * FROM permit_approval WHERE permit_key = ? ORDER BY seq_no",
                APPROVAL_MAPPER, permitKey);
    }

    // ---------- 幂等命令 ----------

    public Optional<CommandRow> findCommand(String commandKey) {
        return jdbc.query("SELECT * FROM command_record WHERE command_key = ?",
                COMMAND_MAPPER, commandKey).stream().findFirst();
    }

    public void insertCommand(CommandRow row) {
        jdbc.update("INSERT INTO command_record"
                        + " (command_key, operation, fingerprint, http_status, response_body, created_at)"
                        + " VALUES (?,?,?,?,?,?)",
                row.commandKey(), row.operation(), row.fingerprint(),
                row.httpStatus(), row.responseBody(), row.createdAt());
    }
}
