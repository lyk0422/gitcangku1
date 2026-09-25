package com.example.starter.observation;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 设备时钟偏移持久化：device_registry 提供按设备串行化的行锁载体，
 * device_clock_offset 保存各设备的偏移区间记录。所有 SQL 使用参数化查询。
 */
@Repository
public class DeviceClockRepository {

    private static final RowMapper<DeviceOffsetEntry> OFFSET_MAPPER = (rs, rowNum) -> new DeviceOffsetEntry(
            rs.getString("device_id"),
            rs.getTimestamp("effective_from_utc").toInstant(),
            rs.getInt("offset_seconds"));

    private final JdbcTemplate jdbcTemplate;

    public DeviceClockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 确保设备已登记（幂等）；并发首次登记由主键串行化，后到者忽略冲突。
     */
    public void ensureDevice(String deviceId) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO device_registry (device_id, created_at) VALUES (?, CURRENT_TIMESTAMP)",
                    deviceId);
        } catch (DuplicateKeyException e) {
            // 设备已存在：忽略
        }
    }

    /**
     * 对设备登记行加行锁（SELECT ... FOR UPDATE），串行化同一设备的偏移变更与观测提交。
     * 调用前必须先 ensureDevice。
     */
    public void lockDevice(String deviceId) {
        jdbcTemplate.query(
                "SELECT device_id FROM device_registry WHERE device_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString("device_id"), deviceId);
    }

    /**
     * 按设备查询全部偏移记录，按生效起始时刻升序。
     */
    public List<DeviceOffsetEntry> findOffsets(String deviceId) {
        return jdbcTemplate.query(
                "SELECT device_id, effective_from_utc, offset_seconds FROM device_clock_offset "
                        + "WHERE device_id = ? ORDER BY effective_from_utc ASC",
                OFFSET_MAPPER, deviceId);
    }

    /**
     * 按 (deviceId, effectiveFromUtc) 精确查询偏移记录；不存在时返回空。
     */
    public Optional<DeviceOffsetEntry> findOffset(String deviceId, Instant effectiveFromUtc) {
        return jdbcTemplate.query(
                        "SELECT device_id, effective_from_utc, offset_seconds FROM device_clock_offset "
                                + "WHERE device_id = ? AND effective_from_utc = ?",
                        OFFSET_MAPPER, deviceId, Timestamp.from(effectiveFromUtc))
                .stream().findFirst();
    }

    /**
     * 命中指定本地时刻（按 UTC 时标解释）的偏移记录：生效起始时刻不大于该时刻的最后一条。
     */
    public Optional<DeviceOffsetEntry> matchOffset(String deviceId, Instant localAtAsUtc) {
        return jdbcTemplate.query(
                        "SELECT device_id, effective_from_utc, offset_seconds FROM device_clock_offset "
                                + "WHERE device_id = ? AND effective_from_utc <= ? "
                                + "ORDER BY effective_from_utc DESC LIMIT 1",
                        OFFSET_MAPPER, deviceId, Timestamp.from(localAtAsUtc))
                .stream().findFirst();
    }

    /**
     * 登记新偏移记录；(deviceId, effectiveFromUtc) 主键冲突时抛出重复键异常（区间重叠）。
     */
    public void insertOffset(String deviceId, Instant effectiveFromUtc, int offsetSeconds, String requestId) {
        jdbcTemplate.update(
                "INSERT INTO device_clock_offset (device_id, effective_from_utc, offset_seconds, request_id, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                deviceId, Timestamp.from(effectiveFromUtc), offsetSeconds, requestId);
    }

    /**
     * 修改既有偏移记录的偏移秒数。
     */
    public void updateOffset(String deviceId, Instant effectiveFromUtc, int offsetSeconds, String requestId) {
        jdbcTemplate.update(
                "UPDATE device_clock_offset SET offset_seconds = ?, request_id = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE device_id = ? AND effective_from_utc = ?",
                offsetSeconds, requestId, deviceId, Timestamp.from(effectiveFromUtc));
    }
}
