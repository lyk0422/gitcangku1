package com.example.starter.firmware.repository;

import com.example.starter.firmware.dto.DeviceResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备登记表数据访问。
 */
@Repository
public class DeviceRepository {

    private static final RowMapper<DeviceResponse> MAPPER = (rs, rowNum) -> new DeviceResponse(
            rs.getString("device_id"),
            rs.getString("model"),
            rs.getString("firmware_version"),
            rs.getInt("bucket_no"));

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新设备，device_id 冲突时抛出 DuplicateKeyException。
     */
    public void insert(String deviceId, String model, String firmwareVersion, int bucketNo) {
        jdbc.update("INSERT INTO device (device_id, model, firmware_version, bucket_no) VALUES (?, ?, ?, ?)",
                deviceId, model, firmwareVersion, bucketNo);
    }

    /**
     * 按设备 ID 查询。
     */
    public Optional<DeviceResponse> findById(String deviceId) {
        return jdbc.query("SELECT device_id, model, firmware_version, bucket_no FROM device WHERE device_id = ?",
                        MAPPER, deviceId)
                .stream().findFirst();
    }

    /**
     * 更新设备当前固件版本（仅成功回执调用）。
     */
    public void updateFirmwareVersion(String deviceId, String firmwareVersion) {
        jdbc.update("UPDATE device SET firmware_version = ?, updated_at = CURRENT_TIMESTAMP WHERE device_id = ?",
                firmwareVersion, deviceId);
    }
}
