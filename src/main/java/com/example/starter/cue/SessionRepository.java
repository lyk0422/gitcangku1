package com.example.starter.cue;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 排练场次、冻结快照与执行回报的数据访问。 */
@Repository
public class SessionRepository {

    private static final RowMapper<RehearsalSession> SESSION_MAPPER = (rs, n) -> new RehearsalSession(
            rs.getLong("id"),
            rs.getLong("version_id"),
            SessionStatus.valueOf(rs.getString("status")),
            rs.getInt("last_executed_seq"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            Optional.ofNullable(rs.getTimestamp("started_at")).map(Timestamp::toLocalDateTime).orElse(null),
            Optional.ofNullable(rs.getTimestamp("finished_at")).map(Timestamp::toLocalDateTime).orElse(null));

    private static final RowMapper<SessionCue> SNAPSHOT_MAPPER = (rs, n) -> new SessionCue(
            rs.getLong("session_id"),
            rs.getInt("seq"),
            CueType.valueOf(rs.getString("cue_type")),
            rs.getString("trigger_phrase"),
            rs.getInt("duration_seconds"));

    private static final RowMapper<CueExecution> EXECUTION_MAPPER = (rs, n) -> new CueExecution(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getInt("seq"),
            rs.getString("request_id"),
            rs.getString("payload_hash"),
            rs.getTimestamp("executed_at").toLocalDateTime());

    private static final RowMapper<CueFailure> FAILURE_MAPPER = (rs, n) -> new CueFailure(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getInt("seq"),
            rs.getString("request_id"),
            rs.getString("reason"),
            rs.getTimestamp("reported_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建未开始场次并返回自增ID。 */
    public long insertSession(long versionId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rehearsal_session (version_id, status) VALUES (?, 'SCHEDULED')",
                    new String[] {"id"});
            ps.setLong(1, versionId);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<RehearsalSession> findSession(long id) {
        List<RehearsalSession> rows = jdbc.query("SELECT * FROM rehearsal_session WHERE id = ?",
                SESSION_MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 未开始场次重新绑定版本，仅SCHEDULED状态生效，返回影响行数。 */
    public int rebind(long sessionId, long versionId) {
        return jdbc.update("UPDATE rehearsal_session SET version_id = ?"
                + " WHERE id = ? AND status = 'SCHEDULED'", versionId, sessionId);
    }

    /** 将未开始场次置为进行中，仅SCHEDULED状态生效，返回影响行数。 */
    public int markRunning(long sessionId) {
        return jdbc.update("UPDATE rehearsal_session SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'SCHEDULED'", sessionId);
    }

    /** 批量写入开始时的提示点冻结快照。 */
    public void insertSnapshot(List<SessionCue> cues) {
        jdbc.batchUpdate("INSERT INTO session_cue (session_id, seq, cue_type, trigger_phrase, duration_seconds)"
                        + " VALUES (?, ?, ?, ?, ?)",
                cues, cues.size(),
                (ps, cue) -> {
                    ps.setLong(1, cue.sessionId());
                    ps.setInt(2, cue.seq());
                    ps.setString(3, cue.cueType().name());
                    ps.setString(4, cue.triggerPhrase());
                    ps.setInt(5, cue.durationSeconds());
                });
    }

    /** 按序号升序读取场次冻结快照。 */
    public List<SessionCue> findSnapshot(long sessionId) {
        return jdbc.query("SELECT * FROM session_cue WHERE session_id = ? ORDER BY seq",
                SNAPSHOT_MAPPER, sessionId);
    }

    /** 按幂等键查询已提交的成功执行记录。 */
    public Optional<CueExecution> findExecutionByRequestId(long sessionId, String requestId) {
        List<CueExecution> rows = jdbc.query(
                "SELECT * FROM cue_execution WHERE session_id = ? AND request_id = ?",
                EXECUTION_MAPPER, sessionId, requestId);
        return rows.stream().findFirst();
    }

    /** 写入成功执行记录，唯一约束保证同序号/同幂等键只留一条。 */
    public void insertExecution(long sessionId, int seq, String requestId, String payloadHash) {
        jdbc.update("INSERT INTO cue_execution (session_id, seq, request_id, payload_hash)"
                + " VALUES (?, ?, ?, ?)", sessionId, seq, requestId, payloadHash);
    }

    /** 条件推进已执行序号：仅当当前已提交序号恰为前一序号时成功，返回影响行数。 */
    public int advanceLastExecuted(long sessionId, int seq) {
        return jdbc.update("UPDATE rehearsal_session SET last_executed_seq = ?"
                + " WHERE id = ? AND status = 'RUNNING' AND last_executed_seq = ?", seq, sessionId, seq - 1);
    }

    /** 记录失败回报，不占用幂等键、不推进序号。 */
    public void insertFailure(long sessionId, int seq, String requestId, String reason) {
        jdbc.update("INSERT INTO cue_failure (session_id, seq, request_id, reason)"
                + " VALUES (?, ?, ?, ?)", sessionId, seq, requestId, reason);
    }

    public List<CueExecution> findExecutions(long sessionId) {
        return jdbc.query("SELECT * FROM cue_execution WHERE session_id = ? ORDER BY seq",
                EXECUTION_MAPPER, sessionId);
    }

    public List<CueFailure> findFailures(long sessionId) {
        return jdbc.query("SELECT * FROM cue_failure WHERE session_id = ? ORDER BY id",
                FAILURE_MAPPER, sessionId);
    }

    /** 仅当进行中且已提交序号等于快照总数时结束场次，返回影响行数。 */
    public int finishIfComplete(long sessionId) {
        return jdbc.update("UPDATE rehearsal_session SET status = 'FINISHED', finished_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'RUNNING'"
                + " AND last_executed_seq = (SELECT COUNT(*) FROM session_cue WHERE session_id = ?)",
                sessionId, sessionId);
    }
}