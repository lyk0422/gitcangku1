package com.example.starter.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备会话持久化：维护 device_session 水位与关闭状态。所有 SQL 使用参数化查询。
 */
@Repository
public class DeviceSessionRepository {

    private static final RowMapper<DeviceSession> SESSION_MAPPER = (rs, rowNum) -> new DeviceSession(
            rs.getString("device_id"),
            rs.getString("session_id"),
            rs.getInt("watermark"),
            rs.getBoolean("closed"),
            (Integer) rs.getObject("declared_last_seq"));

    private static final String COLUMNS = "device_id, session_id, watermark, closed, declared_last_seq";

    private final JdbcTemplate jdbcTemplate;

    public DeviceSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取会话状态（不加锁），用于查询接口。
     */
    public Optional<DeviceSession> find(String deviceId, String sessionId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM device_session WHERE device_id = ? AND session_id = ?",
                        SESSION_MAPPER, deviceId, sessionId)
                .stream().findFirst();
    }

    /**
     * 读取会话状态并加行锁（SELECT ... FOR UPDATE），串行化同会话的并发补洞与确认。
     */
    public Optional<DeviceSession> findForUpdate(String deviceId, String sessionId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM device_session WHERE device_id = ? AND session_id = ? FOR UPDATE",
                        SESSION_MAPPER, deviceId, sessionId)
                .stream().findFirst();
    }

    /**
     * 创建新会话：水位 0、未关闭；并发首建由主键串行化。
     */
    public void insert(String deviceId, String sessionId) {
        jdbcTemplate.update(
                "INSERT INTO device_session (device_id, session_id, watermark, closed, declared_last_seq, updated_at) "
                        + "VALUES (?, ?, 0, FALSE, NULL, CURRENT_TIMESTAMP)",
                deviceId, sessionId);
    }

    /**
     * 推进水位到新值。
     */
    public void updateWatermark(String deviceId, String sessionId, int watermark) {
        jdbcTemplate.update(
                "UPDATE device_session SET watermark = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE device_id = ? AND session_id = ?",
                watermark, deviceId, sessionId);
    }

    /**
     * 关闭会话并记录设备声明的末序号。
     */
    public void markClosed(String deviceId, String sessionId, int declaredLastSeq) {
        jdbcTemplate.update(
                "UPDATE device_session SET closed = TRUE, declared_last_seq = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE device_id = ? AND session_id = ?",
                declaredLastSeq, deviceId, sessionId);
    }
}