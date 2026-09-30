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
 * 任务派发记录 JDBC 仓储。
 * (task_id, request_id) 唯一：同请求重放首次派发与凭据版本，换资源/改参数由业务层比对返回 409。
 * 任务状态的条件更新（WHERE status = ?）与本仓储写入均处于先锁事件行、再锁凭据行的写事务内，
 * 保证撤销与执行启动竞争时只依据已提交的凭据状态发生一次状态转移。
 */
@Repository
public class TaskDispatchRepository {

    private final JdbcTemplate jdbc;

    public TaskDispatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TaskDispatch> MAPPER = (rs, n) -> map(rs);

    private static TaskDispatch map(ResultSet rs) throws SQLException {
        Timestamp blockedAt = rs.getTimestamp("blocked_at");
        Timestamp supersededAt = rs.getTimestamp("superseded_at");
        return new TaskDispatch(
                rs.getLong("id"), rs.getLong("incident_id"), rs.getLong("task_id"),
                rs.getString("request_id"), rs.getString("resource_key"),
                rs.getLong("credential_id"), rs.getInt("credential_version"),
                rs.getString("credential_type"), rs.getString("region"),
                rs.getTimestamp("credential_expires_at").toInstant(),
                DispatchStatus.valueOf(rs.getString("status")),
                rs.getString("block_reason"),
                rs.getString("dispatched_by"),
                rs.getTimestamp("dispatched_at").toInstant(),
                blockedAt == null ? null : blockedAt.toInstant(),
                supersededAt == null ? null : supersededAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 插入一条成功派发记录（状态 DISPATCHED），冻结派发当时的凭据版本快照。
     */
    public long insert(TaskDispatch dispatch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO task_dispatches (incident_id, task_id, request_id, resource_key,"
                            + " credential_id, credential_version, credential_type, region,"
                            + " credential_expires_at, status, block_reason, dispatched_by,"
                            + " dispatched_at, blocked_at, superseded_at, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, dispatch.incidentId());
            ps.setLong(2, dispatch.taskId());
            ps.setString(3, dispatch.requestId());
            ps.setString(4, dispatch.resourceKey());
            ps.setLong(5, dispatch.credentialId());
            ps.setInt(6, dispatch.credentialVersion());
            ps.setString(7, dispatch.credentialType());
            ps.setString(8, dispatch.region());
            ps.setTimestamp(9, Timestamp.from(dispatch.credentialExpiresAt()));
            ps.setString(10, dispatch.status().name());
            ps.setString(11, dispatch.blockReason());
            ps.setString(12, dispatch.dispatchedBy());
            ps.setTimestamp(13, Timestamp.from(dispatch.dispatchedAt()));
            ps.setTimestamp(14, dispatch.blockedAt() == null ? null
                    : Timestamp.from(dispatch.blockedAt()));
            ps.setTimestamp(15, dispatch.supersededAt() == null ? null
                    : Timestamp.from(dispatch.supersededAt()));
            ps.setTimestamp(16, Timestamp.from(dispatch.createdAt()));
            ps.setTimestamp(17, Timestamp.from(dispatch.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按任务与 requestId 查询派发记录，用于重放比对。
     */
    public Optional<TaskDispatch> findByRequest(long taskId, String requestId) {
        List<TaskDispatch> rows = jdbc.query(
                "SELECT * FROM task_dispatches WHERE task_id = ? AND request_id = ?",
                MAPPER, taskId, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 查询任务当前有效派发（DISPATCHED 或已转为 BLOCKED 的最近一条），
     * 按 id 倒序取首条。
     */
    public Optional<TaskDispatch> findLatest(long taskId) {
        List<TaskDispatch> rows = jdbc.query(
                "SELECT * FROM task_dispatches WHERE task_id = ? ORDER BY id DESC LIMIT 1",
                MAPPER, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 查询任务全部派发记录（含阻断与被取代记录），按派发顺序返回。
     */
    public List<TaskDispatch> listByTask(long taskId) {
        return jdbc.query("SELECT * FROM task_dispatches WHERE task_id = ? ORDER BY id",
                MAPPER, taskId);
    }

    /**
     * 将任务仍处于 DISPATCHED 的派发记录置为 SUPERSEDED（被新派发取代），
     * 返回受影响行数；已 BLOCKED 的历史记录保持不变。
     */
    public int supersedeOpen(long taskId, Instant at) {
        return jdbc.update(
                "UPDATE task_dispatches SET status = 'SUPERSEDED', superseded_at = ?, updated_at = ?"
                        + " WHERE task_id = ? AND status = 'DISPATCHED'",
                Timestamp.from(at), Timestamp.from(at), taskId);
    }

    /**
     * 将指定派发记录置为 BLOCKED 并记录阻断原因与 UTC 时刻。
     */
    public void markBlocked(long id, String reason, Instant at) {
        jdbc.update(
                "UPDATE task_dispatches SET status = 'BLOCKED', block_reason = ?, blocked_at = ?,"
                        + " updated_at = ? WHERE id = ?",
                reason, Timestamp.from(at), Timestamp.from(at), id);
    }
}
