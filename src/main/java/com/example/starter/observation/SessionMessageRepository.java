package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 会话消息收件记录持久化：session_message 仅记录成功批次的消息，失败批次随事务回滚不留记录。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class SessionMessageRepository {

    private static final RowMapper<SessionMessageRecord> RECORD_MAPPER = (rs, rowNum) -> new SessionMessageRecord(
            rs.getString("device_id"),
            rs.getString("session_id"),
            rs.getInt("seq"),
            rs.getString("content_fingerprint"),
            rs.getString("request_id"),
            rs.getInt("response_status"),
            rs.getString("response_body"));

    private final JdbcTemplate jdbcTemplate;

    public SessionMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按（设备、会话、序号）查询收件记录；不存在时返回空。
     */
    public Optional<SessionMessageRecord> find(String deviceId, String sessionId, int seq) {
        return jdbcTemplate.query(
                        "SELECT device_id, session_id, seq, content_fingerprint, request_id, "
                                + "response_status, response_body FROM session_message "
                                + "WHERE device_id = ? AND session_id = ? AND seq = ?",
                        RECORD_MAPPER, deviceId, sessionId, seq)
                .stream().findFirst();
    }

    /**
     * 查询会话内全部已收件序号，按升序排列，用于水位重算与待补序号查询。
     */
    public List<Integer> listSeqs(String deviceId, String sessionId) {
        return jdbcTemplate.queryForList(
                "SELECT seq FROM session_message WHERE device_id = ? AND session_id = ? ORDER BY seq ASC",
                Integer.class, deviceId, sessionId);
    }

    /**
     * 统计会话内已收件消息条数。
     */
    public int count(String deviceId, String sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_message WHERE device_id = ? AND session_id = ?",
                Integer.class, deviceId, sessionId);
        return count == null ? 0 : count;
    }

    /**
     * 写入一条收件记录；主键冲突时抛出重复键异常，由调用方按并发同序号处理。
     */
    public void insert(SessionMessageRecord record) {
        jdbcTemplate.update(
                "INSERT INTO session_message (device_id, session_id, seq, content_fingerprint, request_id, "
                        + "response_status, response_body, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.deviceId(), record.sessionId(), record.seq(), record.contentFingerprint(),
                record.requestId(), record.responseStatus(), record.responseBody());
    }
}