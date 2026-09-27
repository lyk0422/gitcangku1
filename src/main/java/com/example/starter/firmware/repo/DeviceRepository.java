package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no, group_name)"
                + " VALUES (?, ?, ?, ?, ?)",
                device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                device.groupName());
    }

    public Optional<Device> findById(String deviceId) {
        return jdbc.query("SELECT device_id, model, current_version, bucket_no, group_name"
                        + " FROM device WHERE device_id = ?",
                (rs, rowNum) -> new Device(rs.getString("device_id"), rs.getString("model"),
                        rs.getString("current_version"), rs.getInt("bucket_no"), rs.getString("group_name")),
                deviceId).stream().findFirst();
    }

    /**
     * 持有设备行锁至事务结束，串行化同设备的模块上报等修订。
     */
    public Optional<Device> findByIdForUpdate(String deviceId) {
        return jdbc.query("SELECT device_id, model, current_version, bucket_no, group_name"
                        + " FROM device WHERE device_id = ? FOR UPDATE",
                (rs, rowNum) -> new Device(rs.getString("device_id"), rs.getString("model"),
                        rs.getString("current_version"), rs.getInt("bucket_no"), rs.getString("group_name")),
                deviceId).stream().findFirst();
    }

    public void updateCurrentVersion(String deviceId, String newVersion) {
        jdbc.update("UPDATE device SET current_version = ? WHERE device_id = ?", newVersion, deviceId);
    }
}
