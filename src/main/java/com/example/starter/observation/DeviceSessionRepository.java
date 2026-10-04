package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备会话持久化：维护 device_session 的水位、条数与关闭状态。所有 SQL 使用参数化查询。
 */
@Repository
public class DeviceSessionRepository {

    private static final RowMapper<DeviceSession> SESSION_MAPPER = (rs, rowNum) -> new DeviceSession(
            rs.getString("device_id"),
            rs.getString("session_id"),
            rs.getInt("watermark"),
            rs.getInt("message_count"),
            rs.getBoolean("closed"),
            (Integer) rs.getObject("final_seq"));

    private final JdbcTemplate jdbcTemplate;

    public DeviceSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取会话状态（不加锁），用于查询接口。
     */
    public Optional<DeviceSession> find(String deviceId, String sessionId) {
        return jdbcTemplate.query(
                        "SELECT device_id, session_id, watermark, message_count, closed, final_seq "
                                + "FROM device_session WHERE device_id = ? AND session_id = ?",
                        SESSION_MAPPER, deviceId, sessionId)
                .stream().findFirst();
    }

    /**
     * 读取会话状态并加行锁（SELECT ... FOR UPDATE），用于写事务内串行化同会话的并发批次与关闭。
     */
    public Optional<DeviceSession> findForUpdate(String deviceId, String sessionId) {
        return jdbcTemplate.query(
                        "SELECT device_id, session_id, watermark, message_count, closed, final_seq "
                                + "FROM device_session WHERE device_id = ? AND session_id = ? FOR UPDATE",
                        SESSION_MAPPER, deviceId, sessionId)
                .stream().findFirst();
    }

    /**
     * 插入新会话（水位 0、未关闭）；主键冲突时抛出重复键异常，由调用方重读加锁行。
     */
    public void insert(String deviceId, String sessionId) {
        jdbcTemplate.update(
                "INSERT INTO device_session (device_id, session_id, watermark, message_count, closed, final_seq, "
                        + "created_at, updated_at) VALUES (?, ?, 0, 0, FALSE, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                deviceId, sessionId);
    }

    /**
     * 推进水位并刷新消息条数；与收件记录同事务提交。
     */
    public void updateProgress(String deviceId, String sessionId, int watermark, int messageCount) {
        jdbcTemplate.update(
                "UPDATE device_session SET watermark = ?, message_count = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE device_id = ? AND session_id = ?",
                watermark, messageCount, deviceId, sessionId);
    }

    /**
     * 标记会话关闭并记录声明末序号；仅在调用方确认水位等于末序号后执行。
     */
    public void markClosed(String deviceId, String sessionId, int finalSeq) {
        jdbcTemplate.update(
                "UPDATE device_session SET closed = TRUE, final_seq = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE device_id = ? AND session_id = ?",
                finalSeq, deviceId, sessionId);
    }
}