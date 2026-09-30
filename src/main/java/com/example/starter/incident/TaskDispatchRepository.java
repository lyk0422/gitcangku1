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
 * 任务派发记录 JDBC 仓储。每次派发追加一行不可变快照（区域、凭据版本、UTC 失效时刻），
 * request_id 全局唯一承担派发幂等：同键重放首次任务与凭据版本，换资源/换任务 409。
 */
@Repository
public class TaskDispatchRepository {

    private final JdbcTemplate jdbc;

    public TaskDispatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TaskDispatch> MAPPER = (rs, n) -> map(rs);

    private static TaskDispatch map(ResultSet rs) throws SQLException {
        String blockReason = rs.getString("block_reason");
        Timestamp blockedAt = rs.getTimestamp("blocked_at");
        Timestamp startedAt = rs.getTimestamp("started_at");
        return new TaskDispatch(
                rs.getLong("id"), rs.getLong("task_id"), rs.getInt("seq"),
                rs.getString("request_id"), rs.getString("resource_key"),
                rs.getString("credential_version"), rs.getString("credential_type"),
                rs.getString("region"), rs.getTimestamp("expires_at").toInstant(),
                DispatchStatus.valueOf(rs.getString("status")),
                blockReason == null ? null : BlockReason.valueOf(blockReason),
                blockedAt == null ? null : blockedAt.toInstant(),
                rs.getTimestamp("dispatched_at").toInstant(),
                rs.getString("dispatched_by"),
                startedAt == null ? null : startedAt.toInstant());
    }

    /**
     * 追加派发记录，返回生成主键。
     *
     * @throws DuplicateKeyException request_id 已存在（含并发已提交）时抛出
     */
    public long insert(TaskDispatch dispatch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO task_dispatches (task_id, seq, request_id, resource_key,"
                            + " credential_version, credential_type, region, expires_at, status,"
                            + " block_reason, blocked_at, dispatched_at, dispatched_by, started_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, dispatch.taskId());
            ps.setInt(2, dispatch.seq());
            ps.setString(3, dispatch.requestId());
            ps.setString(4, dispatch.resourceKey());
            ps.setString(5, dispatch.credentialVersion());
            ps.setString(6, dispatch.credentialType());
            ps.setString(7, dispatch.region());
            ps.setTimestamp(8, Timestamp.from(dispatch.expiresAt()));
            ps.setString(9, dispatch.status().name());
            ps.setString(10, dispatch.blockReason() == null ? null : dispatch.blockReason().name());
            ps.setTimestamp(11, dispatch.blockedAt() == null ? null : Timestamp.from(dispatch.blockedAt()));
            ps.setTimestamp(12, Timestamp.from(dispatch.dispatchedAt()));
            ps.setString(13, dispatch.dispatchedBy());
            ps.setTimestamp(14, dispatch.startedAt() == null ? null : Timestamp.from(dispatch.startedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按派发 requestId 查询（不加锁），用于同键重放判定。
     */
    public Optional<TaskDispatch> findByRequestId(String requestId) {
        List<TaskDispatch> rows = jdbc.query(
                "SELECT * FROM task_dispatches WHERE request_id = ?", MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按派发 requestId 锁定查询（SELECT ... FOR UPDATE），唯一约束冲突后重查用。
     */
    public Optional<TaskDispatch> findByRequestIdForUpdate(String requestId) {
        List<TaskDispatch> rows = jdbc.query(
                "SELECT * FROM task_dispatches WHERE request_id = ? FOR UPDATE", MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 查询任务全部派发记录，按派发次序返回（首次派发与每次再派发）。
     */
    public List<TaskDispatch> listByTask(long taskId) {
        return jdbc.query("SELECT * FROM task_dispatches WHERE task_id = ? ORDER BY seq",
                MAPPER, taskId);
    }

    /**
     * 任务当前最大派发序号；从未派发返回 0。
     */
    public int maxSeq(long taskId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq),0) FROM task_dispatches WHERE task_id = ?",
                Integer.class, taskId);
        return max == null ? 0 : max;
    }

    /**
     * 条件置为执行中：仅 DISPATCHED 行生效，返回受影响行数。
     */
    public int markExecuting(long id, Instant startedAt) {
        return jdbc.update(
                "UPDATE task_dispatches SET status = 'EXECUTING', started_at = ? WHERE id = ?"
                        + " AND status = 'DISPATCHED'",
                Timestamp.from(startedAt), id);
    }

    /**
     * 条件置为阻断：仅 DISPATCHED 行生效，写入阻断原因，返回受影响行数。
     */
    public int markBlocked(long id, BlockReason reason, Instant blockedAt) {
        return jdbc.update(
                "UPDATE task_dispatches SET status = 'BLOCKED', block_reason = ?, blocked_at = ?"
                        + " WHERE id = ? AND status = 'DISPATCHED'",
                reason.name(), Timestamp.from(blockedAt), id);
    }
}
