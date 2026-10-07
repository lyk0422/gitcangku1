package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备登记表数据访问。启动计数不可递减，档案版本只在计数维护成功后加一。
 */
@Repository
public class DeviceRepository {

    private static final RowMapper<Device> MAPPER = (rs, rowNum) -> new Device(
            rs.getString("device_id"), rs.getString("model"), rs.getString("current_version"),
            rs.getInt("bucket_no"), rs.getLong("boot_count"), rs.getInt("profile_version"));

    private static final String COLUMNS = "device_id, model, current_version, bucket_no,"
            + " boot_count, profile_version";

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Device device) {
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no) VALUES (?, ?, ?, ?)",
                device.deviceId(), device.model(), device.currentVersion(), device.bucketNo());
    }

    public Optional<Device> findById(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ?", MAPPER, deviceId)
                .stream().findFirst();
    }

    /**
     * 行锁读取：计数维护、领取冻结与挑战回执借此串行裁决。
     */
    public Optional<Device> findByIdForUpdate(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ? FOR UPDATE",
                MAPPER, deviceId).stream().findFirst();
    }

    public void updateCurrentVersion(String deviceId, String newVersion) {
        jdbc.update("UPDATE device SET current_version = ? WHERE device_id = ?", newVersion, deviceId);
    }

    /**
     * 挑战回执成功：只推进启动计数，不动档案版本；调用方已持设备行锁并校验计数更大。
     */
    public void updateBootCount(String deviceId, long bootCount) {
        jdbc.update("UPDATE device SET boot_count = ? WHERE device_id = ?", bootCount, deviceId);
    }

    /**
     * 计数维护：仅当档案版本匹配时更新计数并加一，返回影响行数；调用方已持设备行锁。
     */
    public int maintainBootCount(String deviceId, long bootCount, int expectedDeviceVersion) {
        return jdbc.update("UPDATE device SET boot_count = ?, profile_version = profile_version + 1"
                + " WHERE device_id = ? AND profile_version = ?", bootCount, deviceId, expectedDeviceVersion);
    }
}
