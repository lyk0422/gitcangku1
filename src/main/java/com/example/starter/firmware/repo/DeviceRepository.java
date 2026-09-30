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
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no) VALUES (?, ?, ?, ?)",
                device.deviceId(), device.model(), device.currentVersion(), device.bucketNo());
    }

    public Optional<Device> findById(String deviceId) {
        return jdbc.query("SELECT device_id, model, current_version, bucket_no FROM device WHERE device_id = ?",
                (rs, rowNum) -> new Device(rs.getString("device_id"), rs.getString("model"),
                        rs.getString("current_version"), rs.getInt("bucket_no")),
                deviceId).stream().findFirst();
    }

    /**
     * 锁定设备行（SELECT ... FOR UPDATE）。画像更新与任务创建统一先锁设备行再锁发布单行，
     * 形成一致的加锁顺序，避免死锁并保证每个任务只引用一个完整画像版本。
     */
    public Optional<Device> findByIdForUpdate(String deviceId) {
        return jdbc.query(
                "SELECT device_id, model, current_version, bucket_no FROM device WHERE device_id = ? FOR UPDATE",
                (rs, rowNum) -> new Device(rs.getString("device_id"), rs.getString("model"),
                        rs.getString("current_version"), rs.getInt("bucket_no")),
                deviceId).stream().findFirst();
    }

    public void updateCurrentVersion(String deviceId, String newVersion) {
        jdbc.update("UPDATE device SET current_version = ? WHERE device_id = ?", newVersion, deviceId);
    }
}
