package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 设备登记表数据访问。型号、硬件型号与分桶号登记后不可修改。
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
     * 已登记设备中出现过的全部硬件型号（用于兼容矩阵配置的未知型号校验）。
     */
    public List<String> findDistinctHardwareModels() {
        return jdbc.query("SELECT DISTINCT hardware_model FROM device",
                (rs, rowNum) -> rs.getString(1));
    }

    /**
     * 发布单候选设备：型号匹配、当前版本等于来源版本且分桶号落入投放比例。
     */
    public List<Device> findCandidates(String model, String fromVersion, int ratio) {
        return jdbc.query("SELECT device_id, model, hardware_model, current_version, bucket_no"
                        + " FROM device WHERE model = ? AND current_version = ? AND bucket_no < ?",
                (rs, rowNum) -> new Device(rs.getString("device_id"), rs.getString("model"),
                        rs.getString("hardware_model"), rs.getString("current_version"), rs.getInt("bucket_no")),
                model, fromVersion, ratio);
    }
}
