package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.DeviceTrust;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 设备信任集合数据访问。每设备至多一条最终信任状态记录。
 */
@Repository
public class DeviceTrustRepository {

    private final JdbcTemplate jdbc;

    public DeviceTrustRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(String deviceId, String anchorFingerprint, boolean trusted) {
        jdbc.update("MERGE INTO device_trust (device_id, anchor_fingerprint, trusted, updated_at)"
                + " KEY (device_id) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                deviceId, anchorFingerprint, trusted ? 1 : 0);
    }

    public Optional<DeviceTrust> findByDevice(String deviceId) {
        return jdbc.query(
                "SELECT device_id, anchor_fingerprint, trusted FROM device_trust WHERE device_id = ?",
                (rs, n) -> new DeviceTrust(rs.getString("device_id"), rs.getString("anchor_fingerprint"),
                        rs.getInt("trusted") == 1),
                deviceId).stream().findFirst();
    }

    public List<DeviceTrust> findByDevices(List<String> deviceIds) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", deviceIds.stream().map(d -> "?").toList());
        return jdbc.query(
                "SELECT device_id, anchor_fingerprint, trusted FROM device_trust WHERE device_id IN ("
                        + placeholders + ")",
                (rs, n) -> new DeviceTrust(rs.getString("device_id"), rs.getString("anchor_fingerprint"),
                        rs.getInt("trusted") == 1),
                deviceIds.toArray());
    }
}
