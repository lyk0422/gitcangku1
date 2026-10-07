package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 设备合成配置数据访问。键在设备内唯一，单设备至多 10 个键，值取值 0~1000000。
 * 调用方须持有设备行锁，保证读取-修改-写入的一致性。
 */
@Repository
public class DeviceConfigRepository {

    private final JdbcTemplate jdbc;

    public DeviceConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取设备完整配置，按键排序。
     */
    public Map<String, Integer> findByDevice(String deviceId) {
        List<Map.Entry<String, Integer>> rows = jdbc.query(
                "SELECT config_key, config_value FROM device_config WHERE device_id = ? ORDER BY config_key",
                (rs, rowNum) -> Map.entry(rs.getString("config_key"), rs.getInt("config_value")),
                deviceId);
        Map<String, Integer> config = new TreeMap<>();
        rows.forEach(e -> config.put(e.getKey(), e.getValue()));
        return config;
    }

    /**
     * 逐键设置：存在则覆盖值，不存在则新增。调用方已校验合并后键数上限。
     */
    public void upsert(String deviceId, Map<String, Integer> entries) {
        for (Map.Entry<String, Integer> entry : new TreeMap<>(entries).entrySet()) {
            int updated = jdbc.update("UPDATE device_config SET config_value = ? WHERE device_id = ? AND config_key = ?",
                    entry.getValue(), deviceId, entry.getKey());
            if (updated == 0) {
                jdbc.update("INSERT INTO device_config (device_id, config_key, config_value) VALUES (?, ?, ?)",
                        deviceId, entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 整体替换设备配置：清空后写入给定完整快照。仅 SUCCESS 回执应用迁移结果时使用。
     */
    public void replaceAll(String deviceId, Map<String, Integer> config) {
        jdbc.update("DELETE FROM device_config WHERE device_id = ?", deviceId);
        for (Map.Entry<String, Integer> entry : new TreeMap<>(config).entrySet()) {
            jdbc.update("INSERT INTO device_config (device_id, config_key, config_value) VALUES (?, ?, ?)",
                    deviceId, entry.getKey(), entry.getValue());
        }
    }

    public void deleteByDevice(String deviceId) {
        jdbc.update("DELETE FROM device_config WHERE device_id = ?", deviceId);
    }
}
