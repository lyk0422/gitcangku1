package com.example.starter.cue.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.cue.domain.CueReport;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 执行回报数据访问。
 * 两个唯一约束共同保证业务规则：
 * {@code uq_cue_report_request}（场次+requestId）幂等键；
 * {@code uq_cue_report_success}（场次+success_seq）同一提示点只留一条成功记录，
 * 且失败回报 success_seq 为 NULL，多条 NULL 在唯一索引中不冲突。
 */
@Repository
public class CueReportRepository {

    private final JdbcTemplate jdbcTemplate;

    public CueReportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条回报；成功时 success_seq 占用序号，失败时为 NULL。
     * 命中任一唯一约束时抛 {@link DuplicateKeyException}，由服务层区分语义。
     */
    public long insert(long sessionId, int seqNo, boolean success, String failReason,
                       String requestId, String operatorCode, LocalDateTime reportedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO cue_report "
                            + "(session_id, seq_no, success, fail_reason, request_id, operator_code, "
                            + "reported_at, success_seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, sessionId);
            ps.setInt(2, seqNo);
            ps.setBoolean(3, success);
            ps.setString(4, failReason);
            ps.setString(5, requestId);
            ps.setString(6, operatorCode);
            ps.setTimestamp(7, Timestamp.valueOf(reportedAt));
            if (success) {
                ps.setInt(8, seqNo);
            } else {
                ps.setNull(8, java.sql.Types.INTEGER);
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按幂等键查询同场次已有回报，不存在返回 null。
     */
    public CueReport findByRequestId(long sessionId, String requestId) {
        List<CueReport> list = jdbcTemplate.query(
                baseSelect() + " WHERE session_id = ? AND request_id = ?",
                (rs, rowNum) -> mapRow(rs),
                sessionId, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 按主键查询，不存在返回 null。
     */
    public CueReport findById(long id) {
        List<CueReport> list = jdbcTemplate.query(
                baseSelect() + " WHERE id = ?",
                (rs, rowNum) -> mapRow(rs),
                id);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 查询场次全部回报，按序号、受理时间升序，供结果聚合。
     */
    public List<CueReport> findBySession(long sessionId) {
        return jdbcTemplate.query(
                baseSelect() + " WHERE session_id = ? ORDER BY seq_no ASC, reported_at ASC, id ASC",
                (rs, rowNum) -> mapRow(rs),
                sessionId);
    }

    private String baseSelect() {
        return "SELECT id, session_id, seq_no, success, fail_reason, request_id, operator_code, reported_at "
                + "FROM cue_report";
    }

    private CueReport mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CueReport(
                rs.getLong("id"),
                rs.getLong("session_id"),
                rs.getInt("seq_no"),
                rs.getBoolean("success"),
                rs.getString("fail_reason"),
                rs.getString("request_id"),
                rs.getString("operator_code"),
                rs.getTimestamp("reported_at").toLocalDateTime());
    }
}
