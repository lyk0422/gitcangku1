package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备登记表数据访问。安全底线单调不降：维护与回执通过行锁（SELECT ... FOR UPDATE）
 * 读取后条件更新，保证并发下取两者最大且不丢失档案版本递增。
 */
@Repository
public class DeviceRepository {

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Device device) {
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no, security_floor,"
                        + " security_profile_version) VALUES (?, ?, ?, ?, ?, 1)",
                device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                device.securityFloor());
    }

    public Optional<Device> findById(String deviceId) {
        return jdbc.query("SELECT device_id, model, current_version, bucket_no, security_floor,"
                        + " security_profile_version FROM device WHERE device_id = ?",
                (rs, rowNum) -> map(rs), deviceId).stream().findFirst();
    }

    public Optional<Device> findByIdForUpdate(String deviceId) {
        return jdbc.query("SELECT device_id, model, current_version, bucket_no, security_floor,"
                        + " security_profile_version FROM device WHERE device_id = ? FOR UPDATE",
                (rs, rowNum) -> map(rs), deviceId).stream().findFirst();
    }

    private Device map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Device(rs.getString("device_id"), rs.getString("model"),
                rs.getString("current_version"), rs.getInt("bucket_no"),
                rs.getInt("security_floor"), rs.getInt("security_profile_version"));
    }

    public void updateCurrentVersion(String deviceId, String newVersion) {
        jdbc.update("UPDATE device SET current_version = ? WHERE device_id = ?", newVersion, deviceId);
    }

    /**
     * 维护提升安全底线：仅当安全档案版本匹配且底线不降时生效，档案版本加一。
     */
    public int raiseSecurityFloor(String deviceId, int expectedProfileVersion, int newFloor) {
        return jdbc.update("UPDATE device SET security_floor = ?,"
                        + " security_profile_version = security_profile_version + 1"
                        + " WHERE device_id = ? AND security_profile_version = ? AND security_floor <= ?",
                newFloor, deviceId, expectedProfileVersion, newFloor);
    }

    /**
     * 首次成功安装提交：更新固件版本、底线取两者最大、安全档案版本加一。调用方须已持有设备行锁。
     */
    public void completeInstall(String deviceId, String newVersion, int newFloor) {
        jdbc.update("UPDATE device SET current_version = ?, security_floor = ?,"
                        + " security_profile_version = security_profile_version + 1 WHERE device_id = ?",
                newVersion, newFloor, deviceId);
    }
}
