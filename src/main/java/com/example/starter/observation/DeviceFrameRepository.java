package com.example.starter.observation;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备坐标基准持久化：device_frame 记录设备当前登记的基准版本。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class DeviceFrameRepository {

    private static final RowMapper<DeviceFrame> DEVICE_MAPPER = (rs, rowNum) -> new DeviceFrame(
            rs.getString("device_id"),
            rs.getString("frame_version"));

    private final JdbcTemplate jdbcTemplate;

    public DeviceFrameRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按设备标识查询登记记录（不加锁）；不存在时返回空。
     */
    public Optional<DeviceFrame> find(String deviceId) {
        return jdbcTemplate.query(
                        "SELECT device_id, frame_version FROM device_frame WHERE device_id = ?",
                        DEVICE_MAPPER, deviceId)
                .stream().findFirst();
    }

    /**
     * 按设备标识查询登记记录并加行锁（SELECT ... FOR UPDATE），用于基准变更事务内串行化。
     */
    public Optional<DeviceFrame> findForUpdate(String deviceId) {
        return jdbcTemplate.query(
                        "SELECT device_id, frame_version FROM device_frame WHERE device_id = ? FOR UPDATE",
                        DEVICE_MAPPER, deviceId)
                .stream().findFirst();
    }

    /**
     * 插入设备登记记录；设备已存在时抛出重复键异常。
     */
    public void insert(String deviceId, String frameVersion) {
        jdbcTemplate.update(
                "INSERT INTO device_frame (device_id, frame_version, created_at, updated_at) "
                        + "VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                deviceId, frameVersion);
    }

    /**
     * 设备未登记时按给定基准版本登记；已登记时保持原记录不变（提交观测不改变设备基准）。
     */
    public void insertIfAbsent(String deviceId, String frameVersion) {
        try {
            insert(deviceId, frameVersion);
        } catch (DuplicateKeyException ignored) {
            // 设备已登记：提交观测不改动设备基准版本
        }
    }

    /**
     * 更新设备基准版本。
     */
    public void updateFrameVersion(String deviceId, String frameVersion) {
        jdbcTemplate.update(
                "UPDATE device_frame SET frame_version = ?, updated_at = CURRENT_TIMESTAMP WHERE device_id = ?",
                frameVersion, deviceId);
    }
}
