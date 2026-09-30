package com.example.starter.cue.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.cue.domain.RehearsalSession;
import com.example.starter.cue.domain.SessionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 排练场次数据访问；并发回报通过 {@code SELECT ... FOR UPDATE} 行锁串行化。
 */
@Repository
public class RehearsalSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public RehearsalSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 创建未开始场次，返回自增主键。
     */
    public long insert(String playCode, String sessionCode, Long versionId, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rehearsal_session "
                            + "(play_code, session_code, version_id, status, total_cues, executed_seq, "
                            + "frozen_version_id, frozen_at, completed_at, created_at) "
                            + "VALUES (?, ?, ?, ?, 0, 0, NULL, NULL, NULL, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, playCode);
            ps.setString(2, sessionCode);
            if (versionId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, versionId);
            }
            ps.setString(4, SessionStatus.NOT_STARTED.name());
            ps.setTimestamp(5, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按剧目与场次编码查询，不存在返回 null。
     */
    public RehearsalSession find(String playCode, String sessionCode) {
        List<RehearsalSession> list = jdbcTemplate.query(
                baseSelect() + " WHERE play_code = ? AND session_code = ?",
                (rs, rowNum) -> mapRow(rs),
                playCode, sessionCode);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 按主键查询，不存在返回 null。
     */
    public RehearsalSession findById(long sessionId) {
        List<RehearsalSession> list = jdbcTemplate.query(
                baseSelect() + " WHERE id = ?",
                (rs, rowNum) -> mapRow(rs),
                sessionId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 行锁查询，供回报/结束等需要串行化的事务使用。
     */
    public RehearsalSession findByIdForUpdate(long sessionId) {
        List<RehearsalSession> list = jdbcTemplate.query(
                baseSelect() + " WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> mapRow(rs),
                sessionId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 未开始场次重新绑定版本。
     */
    public void updateVersion(long sessionId, long versionId) {
        jdbcTemplate.update(
                "UPDATE rehearsal_session SET version_id = ? WHERE id = ? AND status = 'NOT_STARTED'",
                versionId, sessionId);
    }

    /**
     * 开始排练：冻结版本、写入快照总数、置为运行中。仅未开始场次可更新。
     *
     * @return 实际更新行数；0 表示场次已被并发冻结
     */
    public int freeze(long sessionId, long versionId, int totalCues, LocalDateTime frozenAt) {
        return jdbcTemplate.update(
                "UPDATE rehearsal_session SET version_id = ?, frozen_version_id = ?, "
                        + "total_cues = ?, status = 'RUNNING', frozen_at = ? "
                        + "WHERE id = ? AND status = 'NOT_STARTED'",
                versionId, versionId, totalCues, Timestamp.valueOf(frozenAt), sessionId);
    }

    /**
     * 成功回报后推进已执行序号。
     */
    public void advanceSeq(long sessionId, int executedSeq) {
        jdbcTemplate.update(
                "UPDATE rehearsal_session SET executed_seq = ? WHERE id = ?",
                executedSeq, sessionId);
    }

    /**
     * 结束本场：置 FINISHED 并记录结束时间。仅运行中场次可更新。
     */
    public void finish(long sessionId, LocalDateTime completedAt) {
        jdbcTemplate.update(
                "UPDATE rehearsal_session SET status = 'FINISHED', completed_at = ? "
                        + "WHERE id = ? AND status = 'RUNNING'",
                Timestamp.valueOf(completedAt), sessionId);
    }

    private String baseSelect() {
        return "SELECT id, play_code, session_code, version_id, status, total_cues, executed_seq, "
                + "frozen_version_id, frozen_at, completed_at, created_at FROM rehearsal_session";
    }

    private RehearsalSession mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Long versionId = rs.getObject("version_id", Long.class);
        Long frozenVersionId = rs.getObject("frozen_version_id", Long.class);
        Timestamp frozenAt = rs.getTimestamp("frozen_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new RehearsalSession(
                rs.getLong("id"),
                rs.getString("play_code"),
                rs.getString("session_code"),
                versionId,
                SessionStatus.valueOf(rs.getString("status")),
                rs.getInt("total_cues"),
                rs.getInt("executed_seq"),
                frozenVersionId,
                frozenAt == null ? null : frozenAt.toLocalDateTime(),
                completedAt == null ? null : completedAt.toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
