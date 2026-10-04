package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备登记表数据访问。维护更新与增量安装通过行锁（SELECT ... FOR UPDATE）与拉取、回执串行。
 */
@Repository
public class DeviceRepository {

    private static final RowMapper<Device> MAPPER = (rs, rowNum) -> new Device(
            rs.getString("device_id"), rs.getString("model"), rs.getString("current_version"),
            rs.getString("current_digest"), rs.getInt("image_generation"), rs.getInt("bucket_no"));

    private static final String COLUMNS = "device_id, model, current_version, current_digest,"
            + " image_generation, bucket_no";

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Device device) {
        jdbc.update("INSERT INTO device (device_id, model, current_version, current_digest,"
                        + " image_generation, bucket_no) VALUES (?, ?, ?, ?, ?, ?)",
                device.deviceId(), device.model(), device.currentVersion(), device.currentDigest(),
                device.imageGeneration(), device.bucketNo());
    }

    public Optional<Device> findById(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ?",
                MAPPER, deviceId).stream().findFirst();
    }

    public Optional<Device> findByIdForUpdate(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ? FOR UPDATE",
                MAPPER, deviceId).stream().findFirst();
    }

    public void updateCurrentVersion(String deviceId, String newVersion) {
        jdbc.update("UPDATE device SET current_version = ? WHERE device_id = ?", newVersion, deviceId);
    }

    /**
     * 本地维护更新：仅更新当前版本与摘要并使镜像代次加一，型号与分桶号不可修改。
     */
    public void applyMaintenance(String deviceId, String newVersion, String newDigest) {
        jdbc.update("UPDATE device SET current_version = ?, current_digest = ?,"
                        + " image_generation = image_generation + 1 WHERE device_id = ?",
                newVersion, newDigest, deviceId);
    }

    /**
     * 增量安装成功：写入目标版本与目标摘要并使镜像代次加一。
     */
    public void applyDeltaInstall(String deviceId, String newVersion, String targetDigest) {
        jdbc.update("UPDATE device SET current_version = ?, current_digest = ?,"
                        + " image_generation = image_generation + 1 WHERE device_id = ?",
                newVersion, targetDigest, deviceId);
    }
}