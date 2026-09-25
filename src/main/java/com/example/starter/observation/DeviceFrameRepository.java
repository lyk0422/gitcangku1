package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备基准登记持久化：device_frame 每台设备一行，记录当前坐标基准版本。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class DeviceFrameRepository {

    private final JdbcTemplate jdbcTemplate;

    public DeviceFrameRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询设备当前登记的基准版本；未登记时返回空。
     */
    public Optional<String> findFrameVersion(String deviceId) {
        return jdbcTemplate.query(
                        "SELECT frame_version FROM device_frame WHERE device_id = ?",
                        (rs, rowNum) -> rs.getString("frame_version"), deviceId)
                .stream().findFirst();
    }

    /**
     * 查询设备当前登记的基准版本并加行锁（SELECT ... FOR UPDATE），用于基准修改事务内串行化。
     */
    public Optional<String> findFrameVersionForUpdate(String deviceId) {
        return jdbcTemplate.query(
                        "SELECT frame_version FROM device_frame WHERE device_id = ? FOR UPDATE",
                        (rs, rowNum) -> rs.getString("frame_version"), deviceId)
                .stream().findFirst();
    }

    /**
     * 首次登记设备基准版本。
     */
    public void insert(String deviceId, String frameVersion) {
        jdbcTemplate.update(
                "INSERT INTO device_frame (device_id, frame_version, updated_at) "
                        + "VALUES (?, ?, CURRENT_TIMESTAMP)",
                deviceId, frameVersion);
    }

    /**
     * 修改设备基准版本。
     */
    public void update(String deviceId, String frameVersion) {
        jdbcTemplate.update(
                "UPDATE device_frame SET frame_version = ?, updated_at = CURRENT_TIMESTAMP WHERE device_id = ?",
                frameVersion, deviceId);
    }
}
