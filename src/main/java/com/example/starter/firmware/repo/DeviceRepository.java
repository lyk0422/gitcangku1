package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 设备登记表数据访问。健康版本与停机标记只在拓扑波次流程内变更。
 */
@Repository
public class DeviceRepository {

    private static final RowMapper<Device> MAPPER = (rs, rowNum) -> new Device(
            rs.getString("device_id"), rs.getString("model"), rs.getString("current_version"),
            rs.getInt("bucket_no"), rs.getInt("health_version"), rs.getBoolean("down_flag"));

    private static final String COLUMNS = "device_id, model, current_version, bucket_no,"
            + " health_version, down_flag";

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Device device) {
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no,"
                        + " health_version, down_flag) VALUES (?, ?, ?, ?, ?, ?)",
                device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                device.healthVersion(), device.down());
    }

    public Optional<Device> findById(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ?",
                MAPPER, deviceId).stream().findFirst();
    }

    public Optional<Device> findByIdForUpdate(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ? FOR UPDATE",
                MAPPER, deviceId).stream().findFirst();
    }

    public List<Device> findByIds(List<String> deviceIds) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", deviceIds.stream().map(id -> "?").toList());
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id IN (" + placeholders + ")",
                MAPPER, deviceIds.toArray());
    }

    public void updateCurrentVersion(String deviceId, String newVersion) {
        jdbc.update("UPDATE device SET current_version = ? WHERE device_id = ?", newVersion, deviceId);
    }

    /**
     * 设置停机/不健康标记；调用方必须已持有发布单行锁或设备行锁。
     */
    public void updateDown(String deviceId, boolean down) {
        jdbc.update("UPDATE device SET down_flag = ? WHERE device_id = ?", down, deviceId);
    }

    /**
     * 人工恢复：仅当当前为停机且健康版本匹配时生效，健康版本加一并解除停机，返回影响行数。
     */
    public int recoverIfDown(String deviceId, int expectedHealthVersion) {
        return jdbc.update("UPDATE device SET down_flag = FALSE, health_version = health_version + 1"
                + " WHERE device_id = ? AND health_version = ? AND down_flag = TRUE",
                deviceId, expectedHealthVersion);
    }
}
