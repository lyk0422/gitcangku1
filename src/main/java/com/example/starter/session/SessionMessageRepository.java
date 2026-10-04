package com.example.starter.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 会话消息收件记录持久化：仅成功接收的消息落库，失败随事务回滚不留记录。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class SessionMessageRepository {

    private static final RowMapper<SessionMessage> MESSAGE_MAPPER = (rs, rowNum) -> new SessionMessage(
            rs.getString("device_id"),
            rs.getString("session_id"),
            rs.getInt("seq"),
            rs.getString("content_fingerprint"),
            rs.getString("observation_request_id"),
            rs.getInt("response_status"),
            rs.getString("response_body"));

    private static final String COLUMNS = "device_id, session_id, seq, content_fingerprint, "
            + "observation_request_id, response_status, response_body";

    private final JdbcTemplate jdbcTemplate;

    public SessionMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按（设备，会话，序号）查询收件记录；不存在时返回空。
     */
    public Optional<SessionMessage> find(String deviceId, String sessionId, int seq) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM session_message "
                                + "WHERE device_id = ? AND session_id = ? AND seq = ?",
                        MESSAGE_MAPPER, deviceId, sessionId, seq)
                .stream().findFirst();
    }

    /**
     * 判断指定序号是否已收件，用于水位连续推进。
     */
    public boolean exists(String deviceId, String sessionId, int seq) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_message WHERE device_id = ? AND session_id = ? AND seq = ?",
                Integer.class, deviceId, sessionId, seq);
        return count != null && count > 0;
    }

    /**
     * 插入一条收件记录；同（设备，会话，序号）并发写入由主键串行化。
     */
    public void insert(SessionMessage message) {
        jdbcTemplate.update(
                "INSERT INTO session_message (device_id, session_id, seq, content_fingerprint, "
                        + "observation_request_id, response_status, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                message.deviceId(), message.sessionId(), message.seq(), message.contentFingerprint(),
                message.observationRequestId(), message.responseStatus(), message.responseBody());
    }

    /**
     * 查询水位以上已收到的序号，按升序排列。
     */
    public List<Integer> findSeqsAbove(String deviceId, String sessionId, int watermark) {
        return jdbcTemplate.queryForList(
                "SELECT seq FROM session_message WHERE device_id = ? AND session_id = ? AND seq > ? ORDER BY seq",
                Integer.class, deviceId, sessionId, watermark);
    }
}