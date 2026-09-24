package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备登记表数据访问。维护窗口三项（偏移与起止分钟）同时配置或同时为 NULL。
 */
@Repository
public class DeviceRepository {

    private static final String COLUMNS = "device_id, model, current_version, bucket_no, version,"
            + " utc_offset_minutes, window_start_minute, window_end_minute";

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Device map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Device(rs.getString("device_id"), rs.getString("model"),
                rs.getString("current_version"), rs.getInt("bucket_no"), rs.getInt("version"),
                (Integer) rs.getObject("utc_offset_minutes"),
                (Integer) rs.getObject("window_start_minute"),
                (Integer) rs.getObject("window_end_minute"));
    }

    public void insert(Device device) {
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no, version,"
                        + " utc_offset_minutes, window_start_minute, window_end_minute)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                device.version(), device.utcOffsetMinutes(), device.windowStartMinute(),
                device.windowEndMinute());
    }

    public Optional<Device> findById(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ?",
                (rs, rowNum) -> map(rs), deviceId).stream().findFirst();
    }

    public Optional<Device> findByIdForUpdate(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ? FOR UPDATE",
                (rs, rowNum) -> map(rs), deviceId).stream().findFirst();
    }

    public void updateCurrentVersion(String deviceId, String newVersion) {
        jdbc.update("UPDATE device SET current_version = ? WHERE device_id = ?", newVersion, deviceId);
    }

    /**
     * 乐观更新维护窗口：仅当配置版本匹配时生效，版本号加一，返回影响行数。
     */
    public int updateWindow(String deviceId, int expectedVersion, int utcOffsetMinutes,
                            int windowStartMinute, int windowEndMinute) {
        return jdbc.update("UPDATE device SET utc_offset_minutes = ?, window_start_minute = ?,"
                        + " window_end_minute = ?, version = version + 1"
                        + " WHERE device_id = ? AND version = ?",
                utcOffsetMinutes, windowStartMinute, windowEndMinute, deviceId, expectedVersion);
    }
}
