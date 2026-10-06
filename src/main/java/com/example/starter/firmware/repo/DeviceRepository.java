package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备登记表数据访问。安全底线只增不减，安全档案版本从1开始，维护与首次成功安装加一。
 */
@Repository
public class DeviceRepository {

    private static final RowMapper<Device> MAPPER = (rs, rowNum) -> new Device(
            rs.getString("device_id"), rs.getString("model"), rs.getString("current_version"),
            rs.getInt("bucket_no"), rs.getInt("security_floor"), rs.getInt("security_version"));

    private static final String COLUMNS = "device_id, model, current_version, bucket_no,"
            + " security_floor, security_version";

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Device device) {
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no,"
                        + " security_floor, security_version) VALUES (?, ?, ?, ?, ?, 1)",
                device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                device.securityFloor());
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
     * 安全维护：仅当安全档案版本匹配时提升底线并版本加一，返回影响行数。
     */
    public int raiseFloor(String deviceId, int expectedSecurityVersion, int newFloor) {
        return jdbc.update("UPDATE device SET security_floor = ?, security_version = security_version + 1"
                + " WHERE device_id = ? AND security_version = ?",
                newFloor, deviceId, expectedSecurityVersion);
    }

    /**
     * 首次成功安装：更新固件版本、底线取两者最大、安全档案版本加一。调用方必须已持有设备行锁。
     */
    public void applyInstall(String deviceId, String newVersion, int proofIndex) {
        jdbc.update("UPDATE device SET current_version = ?,"
                        + " security_floor = GREATEST(security_floor, ?),"
                        + " security_version = security_version + 1 WHERE device_id = ?",
                newVersion, proofIndex, deviceId);
    }
}
