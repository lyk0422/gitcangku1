package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备登记表数据访问。维护与挑战回执通过 SELECT ... FOR UPDATE 锁设备行串行裁决。
 */
@Repository
public class DeviceRepository {

    private static final String COLUMNS = "device_id, model, current_version, bucket_no, boot_count, device_version";

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Device device) {
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no, boot_count, device_version)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                device.bootCount(), device.deviceVersion());
    }

    public Optional<Device> findById(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ?",
                (rs, rowNum) -> map(rs), deviceId).stream().findFirst();
    }

    /**
     * 锁设备行读取：维护与启动挑战回执共用，保证计数裁决串行。
     */
    public Optional<Device> findByIdForUpdate(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ? FOR UPDATE",
                (rs, rowNum) -> map(rs), deviceId).stream().findFirst();
    }

    public void updateCurrentVersion(String deviceId, String newVersion) {
        jdbc.update("UPDATE device SET current_version = ? WHERE device_id = ?", newVersion, deviceId);
    }

    /**
     * 维护：提升启动计数并推进档案版本；调用方必须已持有设备行锁且完成版本校验。
     */
    public void maintain(String deviceId, long bootCount) {
        jdbc.update("UPDATE device SET boot_count = ?, device_version = device_version + 1"
                + " WHERE device_id = ?", bootCount, deviceId);
    }

    /**
     * 启动挑战成功：原子提升启动计数与当前固件版本（不推进档案版本）；调用方已持有设备行锁。
     */
    public void applyBootInstall(String deviceId, long bootCount, String newVersion) {
        jdbc.update("UPDATE device SET boot_count = ?, current_version = ? WHERE device_id = ?",
                bootCount, newVersion, deviceId);
    }

    private Device map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Device(rs.getString("device_id"), rs.getString("model"),
                rs.getString("current_version"), rs.getInt("bucket_no"),
                rs.getLong("boot_count"), rs.getInt("device_version"));
    }
}
