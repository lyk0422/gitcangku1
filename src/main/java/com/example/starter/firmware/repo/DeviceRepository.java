package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 设备登记表数据访问。标签以规范化 JSON 数组持久化。
 */
@Repository
public class DeviceRepository {

    private static final TypeReference<List<String>> LABEL_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DeviceRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(Device device) {
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no, labels)"
                        + " VALUES (?, ?, ?, ?, ?)",
                device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                writeLabels(device.labels()));
    }

    public Optional<Device> findById(String deviceId) {
        return jdbc.query("SELECT device_id, model, current_version, bucket_no, labels FROM device"
                        + " WHERE device_id = ?",
                (rs, rowNum) -> mapRow(rs), deviceId).stream().findFirst();
    }

    /**
     * 行锁读取单台设备；批量标签修改与发布快照解析期间串行化设备行变更。
     */
    public Optional<Device> findByIdForUpdate(String deviceId) {
        return jdbc.query("SELECT device_id, model, current_version, bucket_no, labels FROM device"
                        + " WHERE device_id = ? FOR UPDATE",
                (rs, rowNum) -> mapRow(rs), deviceId).stream().findFirst();
    }

    public void updateCurrentVersion(String deviceId, String newVersion) {
        jdbc.update("UPDATE device SET current_version = ? WHERE device_id = ?", newVersion, deviceId);
    }

    /**
     * 整体替换设备规范化标签集合。
     */
    public void updateLabels(String deviceId, List<String> labels) {
        jdbc.update("UPDATE device SET labels = ? WHERE device_id = ?", writeLabels(labels), deviceId);
    }

    /**
     * 同型号、同来源版本的候选设备，按 device_id 加行锁并升序返回，供发布单快照解析使用。
     * 调用方应在单事务内调用，使锁覆盖解析与快照写入全过程。
     */
    public List<Device> findCandidatesForUpdate(String model, String fromVersion) {
        return jdbc.query("SELECT device_id, model, current_version, bucket_no, labels FROM device"
                        + " WHERE model = ? AND current_version = ? ORDER BY device_id FOR UPDATE",
                (rs, rowNum) -> mapRow(rs), model, fromVersion);
    }

    private Device mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Device(rs.getString("device_id"), rs.getString("model"),
                rs.getString("current_version"), rs.getInt("bucket_no"), readLabels(rs.getString("labels")));
    }

    private String writeLabels(List<String> labels) {
        try {
            return objectMapper.writeValueAsString(labels == null ? List.of() : labels);
        } catch (Exception e) {
            throw new IllegalStateException("设备标签序列化失败", e);
        }
    }

    private List<String> readLabels(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, LABEL_LIST);
        } catch (Exception e) {
            throw new IllegalStateException("设备标签反序列化失败: " + json, e);
        }
    }
}
