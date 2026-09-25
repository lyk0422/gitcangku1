package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.DeviceStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备登记表数据访问。隔离/解除、拉取、开始、回执共用设备行锁（SELECT ... FOR UPDATE），
 * 全局锁顺序为 设备 → 发布单 → 任务，保证并发按事务提交顺序裁决。
 */
@Repository
public class DeviceRepository {

    private static final RowMapper<Device> MAPPER = (rs, rowNum) -> new Device(
            rs.getString("device_id"), rs.getString("model"), rs.getString("current_version"),
            rs.getInt("bucket_no"), DeviceStatus.valueOf(rs.getString("status")));

    private static final String COLUMNS = "device_id, model, current_version, bucket_no, status";

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Device device) {
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no, status)"
                        + " VALUES (?, ?, ?, ?, ?)",
                device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                device.status().name());
    }

    public Optional<Device> findById(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ?", MAPPER, deviceId)
                .stream().findFirst();
    }

    public Optional<Device> findByIdForUpdate(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ? FOR UPDATE",
                MAPPER, deviceId).stream().findFirst();
    }

    public void updateCurrentVersion(String deviceId, String newVersion) {
        jdbc.update("UPDATE device SET current_version = ? WHERE device_id = ?", newVersion, deviceId);
    }

    /**
     * 更新设备状态（隔离/解除隔离），调用方必须已持有设备行锁。
     */
    public void updateStatus(String deviceId, DeviceStatus status) {
        jdbc.update("UPDATE device SET status = ? WHERE device_id = ?", status.name(), deviceId);
    }

    /**
     * 发布预检：统计型号与当前版本匹配的候选设备数。
     */
    public long countCandidates(String model, String fromVersion) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device WHERE model = ? AND current_version = ?",
                Long.class, model, fromVersion);
        return count == null ? 0 : count;
    }

    /**
     * 发布预检：统计候选设备中处于隔离状态的设备数。
     */
    public long countQuarantinedCandidates(String model, String fromVersion) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device WHERE model = ? AND current_version = ? AND status = 'QUARANTINED'",
                Long.class, model, fromVersion);
        return count == null ? 0 : count;
    }
}
