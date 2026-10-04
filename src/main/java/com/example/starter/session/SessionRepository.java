package com.example.starter.session;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.cue.CueDtos.CueView;

/**
 * 排练场次、冻结快照与执行回报（成功/失败）的持久化访问。
 */
@Repository
public class SessionRepository {

    /** 排练场次的当前状态行。 */
    public record SessionRow(long id, String sessionName, long versionId, String status,
                             int lastExecutedSeq, Instant createdAt, Instant startedAt, Instant finishedAt) {
    }

    /** 成功回报记录，同时充当幂等键登记。 */
    public record SuccessRow(long id, long sessionId, int seqNo, String requestId,
                             String operator, String paramsFingerprint, Instant reportedAt) {
    }

    /** 失败回报记录，不消耗幂等键。 */
    public record FailureRow(long id, long sessionId, int seqNo, String requestId,
                             String operator, String failReason, Instant reportedAt) {
    }

    private static final RowMapper<SessionRow> SESSION_MAPPER = (rs, n) -> new SessionRow(
            rs.getLong("id"),
            rs.getString("session_name"),
            rs.getLong("version_id"),
            rs.getString("status"),
            rs.getInt("last_executed_seq"),
            rs.getTimestamp("created_at").toInstant(),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("finished_at")));

    private static final RowMapper<CueView> CUE_MAPPER = (rs, n) -> new CueView(
            rs.getInt("seq_no"),
            rs.getString("cue_type"),
            rs.getString("trigger_phrase"),
            rs.getInt("duration_seconds"));

    private static final RowMapper<SuccessRow> SUCCESS_MAPPER = (rs, n) -> new SuccessRow(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getInt("seq_no"),
            rs.getString("request_id"),
            rs.getString("operator"),
            rs.getString("params_fingerprint"),
            rs.getTimestamp("reported_at").toInstant());

    private static final RowMapper<FailureRow> FAILURE_MAPPER = (rs, n) -> new FailureRow(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getInt("seq_no"),
            rs.getString("request_id"),
            rs.getString("operator"),
            rs.getString("fail_reason"),
            rs.getTimestamp("reported_at").toInstant());

    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertSession(String sessionName, long versionId, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rehearsal_session (session_name, version_id, status, last_executed_seq, created_at)"
                            + " VALUES (?, ?, 'NOT_STARTED', 0, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sessionName);
            ps.setLong(2, versionId);
            ps.setTimestamp(3, Timestamp.from(createdAt));
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("未能获取新场次主键");
        }
        return key.longValue();
    }

    public Optional<SessionRow> findById(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM rehearsal_session WHERE id = ?", SESSION_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 事务内行锁读取，串行化同一场次的回报与结束判定。 */
    public Optional<SessionRow> lockById(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM rehearsal_session WHERE id = ? FOR UPDATE", SESSION_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void updateVersionId(long id, long versionId) {
        jdbc.update("UPDATE rehearsal_session SET version_id = ? WHERE id = ?", versionId, id);
    }

    public void markStarted(long id, Instant startedAt) {
        jdbc.update("UPDATE rehearsal_session SET status = 'IN_PROGRESS', started_at = ? WHERE id = ?",
                Timestamp.from(startedAt), id);
    }

    public void advanceSeq(long id, int seqNo) {
        jdbc.update("UPDATE rehearsal_session SET last_executed_seq = ? WHERE id = ?", seqNo, id);
    }

    public void markFinished(long id, Instant finishedAt) {
        jdbc.update("UPDATE rehearsal_session SET status = 'FINISHED', finished_at = ? WHERE id = ?",
                Timestamp.from(finishedAt), id);
    }

    public void insertSessionCue(long sessionId, CueView cue) {
        jdbc.update("INSERT INTO session_cue (session_id, seq_no, cue_type, trigger_phrase, duration_seconds)"
                        + " VALUES (?, ?, ?, ?, ?)",
                sessionId, cue.seqNo(), cue.cueType(), cue.triggerPhrase(), cue.durationSeconds());
    }

    public List<CueView> findSessionCues(long sessionId) {
        return jdbc.query("SELECT * FROM session_cue WHERE session_id = ? ORDER BY seq_no",
                CUE_MAPPER, sessionId);
    }

    public int countSessionCues(long sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_cue WHERE session_id = ?", Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    public Optional<SuccessRow> findSuccessByRequestId(long sessionId, String requestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM cue_success WHERE session_id = ? AND request_id = ?",
                    SUCCESS_MAPPER, sessionId, requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void insertSuccess(long sessionId, int seqNo, String requestId, String operator,
                              String paramsFingerprint, Instant reportedAt) {
        jdbc.update("INSERT INTO cue_success (session_id, seq_no, request_id, operator, params_fingerprint, reported_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                sessionId, seqNo, requestId, operator, paramsFingerprint, Timestamp.from(reportedAt));
    }

    public void insertFailure(long sessionId, int seqNo, String requestId, String operator,
                              String failReason, Instant reportedAt) {
        jdbc.update("INSERT INTO cue_failure (session_id, seq_no, request_id, operator, fail_reason, reported_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                sessionId, seqNo, requestId, operator, failReason, Timestamp.from(reportedAt));
    }

    public List<SuccessRow> findSuccesses(long sessionId) {
        return jdbc.query("SELECT * FROM cue_success WHERE session_id = ? ORDER BY seq_no",
                SUCCESS_MAPPER, sessionId);
    }

    public List<FailureRow> findFailures(long sessionId) {
        return jdbc.query("SELECT * FROM cue_failure WHERE session_id = ? ORDER BY id",
                FAILURE_MAPPER, sessionId);
    }

    public int countSuccesses(long sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cue_success WHERE session_id = ?", Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}