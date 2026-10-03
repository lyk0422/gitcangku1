package com.example.starter.cue.repo;

import com.example.starter.cue.domain.CueExecution;
import com.example.starter.cue.domain.RehearsalSession;
import com.example.starter.cue.domain.SessionCue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 排练场次、冻结快照与执行回报的 JDBC 仓库，全部使用参数绑定。
 */
@Repository
public class SessionRepository {

    private static final RowMapper<RehearsalSession> SESSION_MAPPER = (rs, n) -> new RehearsalSession(
            rs.getLong("id"), rs.getString("production"), rs.getLong("version_id"),
            rs.getString("status"),
            rs.getObject("completed") == null ? null : rs.getBoolean("completed"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toLocalDateTime(),
            rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toLocalDateTime());

    private static final RowMapper<SessionCue> CUE_MAPPER = (rs, n) -> new SessionCue(
            rs.getLong("id"), rs.getLong("session_id"), rs.getInt("seq"),
            rs.getString("cue_type"), rs.getString("trigger_phrase"),
            rs.getInt("duration_seconds"));

    private static final RowMapper<CueExecution> EXEC_MAPPER = (rs, n) -> new CueExecution(
            rs.getLong("id"), rs.getLong("session_id"), rs.getInt("seq"),
            rs.getString("request_id"), rs.getString("operator"), rs.getString("result"),
            rs.getString("fail_reason"), rs.getTimestamp("reported_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建未开始场次，返回生成主键。 */
    public long insertSession(String production, long versionId, LocalDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rehearsal_session (production, version_id, status, created_at)"
                            + " VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, production);
            ps.setLong(2, versionId);
            ps.setString(3, RehearsalSession.STATUS_PENDING);
            ps.setTimestamp(4, Timestamp.valueOf(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 按 ID 查询场次。 */
    public Optional<RehearsalSession> findSession(long sessionId) {
        List<RehearsalSession> list = jdbc.query(
                "SELECT * FROM rehearsal_session WHERE id = ?", SESSION_MAPPER, sessionId);
        return list.stream().findFirst();
    }

    /** 按 ID 查询场次并加行级写锁，用于串行化回报与结束。 */
    public Optional<RehearsalSession> findSessionForUpdate(long sessionId) {
        List<RehearsalSession> list = jdbc.query(
                "SELECT * FROM rehearsal_session WHERE id = ? FOR UPDATE", SESSION_MAPPER, sessionId);
        return list.stream().findFirst();
    }

    /** 更新场次绑定版本（仅未开始）。 */
    public void rebindVersion(long sessionId, long versionId) {
        jdbc.update("UPDATE rehearsal_session SET version_id = ? WHERE id = ? AND status = ?",
                versionId, sessionId, RehearsalSession.STATUS_PENDING);
    }

    /** 标记场次开始。 */
    public void markStarted(long sessionId, LocalDateTime startedAt) {
        jdbc.update("UPDATE rehearsal_session SET status = ?, started_at = ? WHERE id = ?",
                RehearsalSession.STATUS_RUNNING, Timestamp.valueOf(startedAt), sessionId);
    }

    /** 标记场次结束并写入完整性结论。 */
    public void markEnded(long sessionId, boolean completed, LocalDateTime endedAt) {
        jdbc.update("UPDATE rehearsal_session SET status = ?, completed = ?, ended_at = ? WHERE id = ?",
                RehearsalSession.STATUS_ENDED, completed, Timestamp.valueOf(endedAt), sessionId);
    }

    /** 冻结提示点快照：把版本明细按序号复制进场次。 */
    public void copyCuesToSession(long sessionId, long versionId) {
        jdbc.update("INSERT INTO session_cue (session_id, seq, cue_type, trigger_phrase, duration_seconds)"
                        + " SELECT ?, seq, cue_type, trigger_phrase, duration_seconds"
                        + " FROM cue_item WHERE version_id = ? ORDER BY seq",
                sessionId, versionId);
    }

    /** 按序号升序查询场次冻结快照。 */
    public List<SessionCue> findSessionCues(long sessionId) {
        return jdbc.query("SELECT * FROM session_cue WHERE session_id = ? ORDER BY seq",
                CUE_MAPPER, sessionId);
    }

    /** 插入一条回报；成功记录写入幂等键与序号占位，失败记录两列均为 NULL。 */
    public long insertExecution(long sessionId, int seq, String requestId, String operator,
                                String result, String failReason, LocalDateTime reportedAt) {
        boolean success = CueExecution.RESULT_SUCCESS.equals(result);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO cue_execution (session_id, seq, request_id, operator, result,"
                            + " fail_reason, success_request_id, success_seq, reported_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, sessionId);
            ps.setInt(2, seq);
            ps.setString(3, requestId);
            ps.setString(4, operator);
            ps.setString(5, result);
            ps.setString(6, failReason);
            if (success) {
                ps.setString(7, requestId);
                ps.setInt(8, seq);
            } else {
                ps.setNull(7, java.sql.Types.VARCHAR);
                ps.setNull(8, java.sql.Types.INTEGER);
            }
            ps.setTimestamp(9, Timestamp.valueOf(reportedAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 查询场次内某幂等键对应的成功回报。 */
    public Optional<CueExecution> findSuccessByRequestId(long sessionId, String requestId) {
        List<CueExecution> list = jdbc.query(
                "SELECT * FROM cue_execution WHERE session_id = ? AND success_request_id = ?",
                EXEC_MAPPER, sessionId, requestId);
        return list.stream().findFirst();
    }

    /** 已提交的最大成功序号；无成功记录返回 0。 */
    public int maxSuccessSeq(long sessionId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(success_seq), 0) FROM cue_execution WHERE session_id = ?",
                Integer.class, sessionId);
        return max;
    }

    /** 成功回报总数。 */
    public int countSuccess(long sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cue_execution WHERE session_id = ? AND result = ?",
                Integer.class, sessionId, CueExecution.RESULT_SUCCESS);
        return count;
    }

    /** 按时间升序查询场次全部回报（含失败）。 */
    public List<CueExecution> findExecutions(long sessionId) {
        return jdbc.query("SELECT * FROM cue_execution WHERE session_id = ? ORDER BY id",
                EXEC_MAPPER, sessionId);
    }
}