package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.DeviceStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备登记表数据访问。隔离操作通过 SELECT ... FOR UPDATE 锁定设备行，
 * 与拉取、回执等操作按事务提交顺序裁决。
 */
@Repository
public class DeviceRepository {

    private static final String COLUMNS = "device_id, model, current_version, bucket_no, status";

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Device device) {
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no, status)"
                + " VALUES (?, ?, ?, ?, 'ACTIVE')",
                device.deviceId(), device.model(), device.currentVersion(), device.bucketNo());
    }

    private Optional<Device> queryOne(String sql, Object... args) {
        return jdbc.query(sql, (rs, rowNum) -> new Device(rs.getString("device_id"), rs.getString("model"),
                rs.getString("current_version"), rs.getInt("bucket_no"),
                DeviceStatus.valueOf(rs.getString("status"))), args).stream().findFirst();
    }

    public Optional<Device> findById(String deviceId) {
        return queryOne("SELECT " + COLUMNS + " FROM device WHERE device_id = ?", deviceId);
    }

    public Optional<Device> findByIdForUpdate(String deviceId) {
        return queryOne("SELECT " + COLUMNS + " FROM device WHERE device_id = ? FOR UPDATE", deviceId);
    }

    public void updateStatus(String deviceId, DeviceStatus status) {
        jdbc.update("UPDATE device SET status = ? WHERE device_id = ?", status.name(), deviceId);
    }

    public void updateCurrentVersion(String deviceId, String newVersion) {
        jdbc.update("UPDATE device SET current_version = ? WHERE device_id = ?", newVersion, deviceId);
    }

    /**
     * 发布候选设备总数：型号与来源版本匹配、分桶号小于比例（不限状态）。
     */
    public long countCandidates(String model, String fromVersion, int ratio) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device WHERE model = ? AND current_version = ? AND bucket_no < ?",
                Long.class, model, fromVersion, ratio);
        return count == null ? 0 : count;
    }

    /**
     * 候选中处于 QUARANTINED 的设备数。
     */
    public long countQuarantinedCandidates(String model, String fromVersion, int ratio) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device WHERE model = ? AND current_version = ? AND bucket_no < ?"
                        + " AND status = 'QUARANTINED'",
                Long.class, model, fromVersion, ratio);
        return count == null ? 0 : count;
    }

    /**
     * 可投放设备数：候选且未被隔离（ACTIVE）。
     */
    public long countDeployableCandidates(String model, String fromVersion, int ratio) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device WHERE model = ? AND current_version = ? AND bucket_no < ?"
                        + " AND status = 'ACTIVE'",
                Long.class, model, fromVersion, ratio);
        return count == null ? 0 : count;
    }

    /**
     * 按 device_id 升序锁定全部候选设备行：发布启动与隔离并发时按事务提交顺序裁决。
     */
    public void lockCandidatesForUpdate(String model, String fromVersion, int ratio) {
        jdbc.query("SELECT device_id FROM device WHERE model = ? AND current_version = ? AND bucket_no < ?"
                        + " ORDER BY device_id FOR UPDATE",
                (rs, rowNum) -> rs.getString("device_id"), model, fromVersion, ratio);
    }
}
