package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备登记表数据访问。拉取、回执与本地配置修改共用设备行锁（SELECT ... FOR UPDATE），
 * 保证配置版本与配置内容读取-修改-写入的一致性。
 */
@Repository
public class DeviceRepository {

    private static final RowMapper<Device> MAPPER = (rs, rowNum) -> new Device(
            rs.getString("device_id"), rs.getString("model"), rs.getString("current_version"),
            rs.getInt("bucket_no"), rs.getInt("config_version"));

    private static final String COLUMNS = "device_id, model, current_version, bucket_no, config_version";

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Device device) {
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no, config_version)"
                        + " VALUES (?, ?, ?, ?, 1)",
                device.deviceId(), device.model(), device.currentVersion(), device.bucketNo());
    }

    public Optional<Device> findById(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ?", MAPPER, deviceId)
                .stream().findFirst();
    }

    /**
     * 设备行锁：拉取创建任务、回执应用迁移、本地配置修改共用，形成一致提交顺序。
     */
    public Optional<Device> findByIdForUpdate(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ? FOR UPDATE",
                MAPPER, deviceId).stream().findFirst();
    }

    public void updateCurrentVersion(String deviceId, String newVersion) {
        jdbc.update("UPDATE device SET current_version = ? WHERE device_id = ?", newVersion, deviceId);
    }

    /**
     * SUCCESS 回执应用迁移：固件版本与配置版本同时推进，配置内容由 DeviceConfigRepository 整体替换。
     */
    public void updateVersionAndConfigVersion(String deviceId, String newVersion) {
        jdbc.update("UPDATE device SET current_version = ?, config_version = config_version + 1"
                + " WHERE device_id = ?", newVersion, deviceId);
    }

    /**
     * 本地配置修改：仅推进配置版本，固件版本不变。
     */
    public void incrementConfigVersion(String deviceId) {
        jdbc.update("UPDATE device SET config_version = config_version + 1 WHERE device_id = ?", deviceId);
    }
}
