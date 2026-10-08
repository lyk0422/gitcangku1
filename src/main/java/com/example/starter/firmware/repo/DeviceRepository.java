package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备登记表数据访问。配置版本与配置项的并发修改通过设备行锁（SELECT ... FOR UPDATE）串行化。
 */
@Repository
public class DeviceRepository {

    private static final String COLUMNS = "device_id, model, current_version, bucket_no, config_version";

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Device device) {
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no, config_version)"
                        + " VALUES (?, ?, ?, ?, ?)",
                device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                device.configVersion());
    }

    public Optional<Device> findById(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ?",
                (rs, rowNum) -> map(rs), deviceId).stream().findFirst();
    }

    /**
     * 设备行锁：配置修改、领取冻结与成功回执应用配置共用，保证不使用领取后变更的旧配置。
     */
    public Optional<Device> findByIdForUpdate(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ? FOR UPDATE",
                (rs, rowNum) -> map(rs), deviceId).stream().findFirst();
    }

    public void updateCurrentVersion(String deviceId, String newVersion) {
        jdbc.update("UPDATE device SET current_version = ? WHERE device_id = ?", newVersion, deviceId);
    }

    public void updateConfigVersion(String deviceId, int configVersion) {
        jdbc.update("UPDATE device SET config_version = ? WHERE device_id = ?", configVersion, deviceId);
    }

    private Device map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Device(rs.getString("device_id"), rs.getString("model"),
                rs.getString("current_version"), rs.getInt("bucket_no"), rs.getInt("config_version"));
    }
}
