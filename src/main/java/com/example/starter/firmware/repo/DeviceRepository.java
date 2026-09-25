package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 设备登记表数据访问。
 */
@Repository
public class DeviceRepository {

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Device device) {
        jdbc.update("INSERT INTO device (device_id, model, hardware_model, current_version, bucket_no)"
                        + " VALUES (?, ?, ?, ?, ?)",
                device.deviceId(), device.model(), device.hardwareModel(),
                device.currentVersion(), device.bucketNo());
    }

    public Optional<Device> findById(String deviceId) {
        return jdbc.query("SELECT device_id, model, hardware_model, current_version, bucket_no"
                        + " FROM device WHERE device_id = ?",
                (rs, rowNum) -> new Device(rs.getString("device_id"), rs.getString("model"),
                        rs.getString("hardware_model"), rs.getString("current_version"), rs.getInt("bucket_no")),
                deviceId).stream().findFirst();
    }

    public void updateCurrentVersion(String deviceId, String newVersion) {
        jdbc.update("UPDATE device SET current_version = ? WHERE device_id = ?", newVersion, deviceId);
    }

    /**
     * 发布预检候选设备：产品型号匹配且当前版本等于来源版本，返回其不可变硬件型号。
     */
    public List<String> findCandidateHardwareModels(String model, String fromVersion) {
        return jdbc.queryForList(
                "SELECT hardware_model FROM device WHERE model = ? AND current_version = ?"
                        + " ORDER BY hardware_model",
                String.class, model, fromVersion);
    }
}
