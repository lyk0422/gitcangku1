package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 设备会话与收件记录持久化：维护 device_session 水位状态与 session_inbox 收件记录。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class DeviceSessionRepository {

    private static final RowMapper<DeviceSession> SESSION_MAPPER = (rs, rowNum) -> new DeviceSession(
            rs.getString("device_id"),
            rs.getString("session_id"),
            rs.getInt("watermark"),
            rs.getInt("message_count"),
            rs.getBoolean("closed"),
            (Integer) rs.getObject("closed_seq"));

    private static final RowMapper<InboxMessage> INBOX_MAPPER = (rs, rowNum) -> new InboxMessage(
            rs.getInt("seq"),
            rs.getString("content_fingerprint"),
            rs.getString("batch_request_id"),
            rs.getInt("message_status"),
            rs.getString("message_body"));

    private final JdbcTemplate jdbcTemplate;

    public DeviceSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取会话状态（不加锁），用于查询接口。
     */
    public Optional<DeviceSession> find(String deviceId, String sessionId) {
        return jdbcTemplate.query(
                        "SELECT device_id, session_id, watermark, message_count, closed, closed_seq "
                                + "FROM device_session WHERE device_id = ? AND session_id = ?",
                        SESSION_MAPPER, deviceId, sessionId)
                .stream().findFirst();
    }

    /**
     * 读取会话状态并加行锁（SELECT ... FOR UPDATE），用于写事务内串行化同会话的并发批次与关闭。
     */
    public Optional<DeviceSession> findForUpdate(String deviceId, String sessionId) {
        return jdbcTemplate.query(
                        "SELECT device_id, session_id, watermark, message_count, closed, closed_seq "
                                + "FROM device_session WHERE device_id = ? AND session_id = ? FOR UPDATE",
                        SESSION_MAPPER, deviceId, sessionId)
                .stream().findFirst();
    }

    /**
     * 插入新会话（水位 0、计数 0、未关闭）；并发首建由主键串行化。
     */
    public void insertSession(String deviceId, String sessionId) {
        jdbcTemplate.update(
                "INSERT INTO device_session (device_id, session_id, watermark, message_count, closed, closed_seq, updated_at) "
                        + "VALUES (?, ?, 0, 0, FALSE, NULL, CURRENT_TIMESTAMP)",
                deviceId, sessionId);
    }

    /**
     * 推进水位并累加收件计数。
     */
    public void advanceWatermark(String deviceId, String sessionId, int watermark, int messageCount) {
        jdbcTemplate.update(
                "UPDATE device_session SET watermark = ?, message_count = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE device_id = ? AND session_id = ?",
                watermark, messageCount, deviceId, sessionId);
    }

    /**
     * 标记会话关闭并记录声明末序号。
     */
    public void markClosed(String deviceId, String sessionId, int closedSeq) {
        jdbcTemplate.update(
                "UPDATE device_session SET closed = TRUE, closed_seq = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE device_id = ? AND session_id = ?",
                closedSeq, deviceId, sessionId);
    }

    /**
     * 读取会话全部收件记录（按序号升序）；会话上限 100 条，结果集有界。
     */
    public List<InboxMessage> findInbox(String deviceId, String sessionId) {
        return jdbcTemplate.query(
                "SELECT seq, content_fingerprint, batch_request_id, message_status, message_body "
                        + "FROM session_inbox WHERE device_id = ? AND session_id = ? ORDER BY seq",
                INBOX_MAPPER, deviceId, sessionId);
    }

    /**
     * 写入一条收件记录；同序号并发写入由主键串行化。
     */
    public void insertInbox(String deviceId, String sessionId, int seq, String contentFingerprint,
                            String batchRequestId, int messageStatus, String messageBody) {
        jdbcTemplate.update(
                "INSERT INTO session_inbox (device_id, session_id, seq, content_fingerprint, batch_request_id, "
                        + "message_status, message_body, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                deviceId, sessionId, seq, contentFingerprint, batchRequestId, messageStatus, messageBody);
    }

    /**
     * 查询水位以上已收到的序号，升序排列。
     */
    public List<Integer> findPendingSeqs(String deviceId, String sessionId, int watermark) {
        return jdbcTemplate.queryForList(
                "SELECT seq FROM session_inbox WHERE device_id = ? AND session_id = ? AND seq > ? ORDER BY seq",
                Integer.class, deviceId, sessionId, watermark);
    }
}