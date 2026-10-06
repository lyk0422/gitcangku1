package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.HealthStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 设备登记表数据访问。健康状态只在发布单行锁或设备行锁内变更。
 */
@Repository
public class DeviceRepository {

    private static final RowMapper<Device> MAPPER = (rs, rowNum) -> new Device(
            rs.getString("device_id"), rs.getString("model"), rs.getString("current_version"),
            rs.getInt("bucket_no"), HealthStatus.valueOf(rs.getString("health_status")),
            rs.getInt("health_version"));

    private static final String COLUMNS = "device_id, model, current_version, bucket_no,"
            + " health_status, health_version";

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

    public Optional<Device> findByIdForUpdate(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ? FOR UPDATE",
                MAPPER, deviceId).stream().findFirst();
    }

    /**
     * 按ID集合加行锁读取（按设备ID排序，避免死锁），波次领取时冻结健康判断。
     */
    public List<Device> findByIdsForUpdate(List<String> deviceIds) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", deviceIds.stream().map(id -> "?").toList());
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id IN (" + placeholders
                        + ") ORDER BY device_id FOR UPDATE",
                MAPPER, deviceIds.toArray());
    }

    public void updateCurrentVersion(String deviceId, String newVersion) {
        jdbc.update("UPDATE device SET current_version = ? WHERE device_id = ?", newVersion, deviceId);
    }

    /**
     * 更新健康状态（波次停机、回执恢复/失败），调用方需持有发布单行锁或设备行锁。
     */
    public void updateHealthStatus(String deviceId, HealthStatus status) {
        jdbc.update("UPDATE device SET health_status = ? WHERE device_id = ?", status.name(), deviceId);
    }

    /**
     * 人工恢复：仅当健康版本匹配且仍为 UNHEALTHY 时生效，恢复健康并版本加一，返回影响行数。
     */
    public int recoverIfUnhealthy(String deviceId, int expectedHealthVersion) {
        return jdbc.update("UPDATE device SET health_status = 'HEALTHY', health_version = health_version + 1"
                + " WHERE device_id = ? AND health_version = ? AND health_status = 'UNHEALTHY'",
                deviceId, expectedHealthVersion);
    }

    /**
     * 取消发布单时释放停机：仍处于 DOWN 且任务待执行的设备恢复健康；失败设备不自动恢复。
     */
    public int releaseDownByRelease(long releaseId) {
        return jdbc.update("UPDATE device SET health_status = 'HEALTHY' WHERE health_status = 'DOWN'"
                + " AND device_id IN (SELECT device_id FROM rollout_task"
                + " WHERE release_id = ? AND status = 'PENDING')", releaseId);
    }
}
