package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.TreeMap;

/**
 * 设备配置项数据访问。配置按设备整体替换（先删后插），调用方须持有设备行锁。
 */
@Repository
public class DeviceConfigRepository {

    private final JdbcTemplate jdbc;

    public DeviceConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取设备完整配置，返回键升序的有序映射，保证快照与指纹确定性。
     */
    public Map<String, Integer> findByDevice(String deviceId) {
        Map<String, Integer> entries = new TreeMap<>();
        jdbc.query("SELECT cfg_key, cfg_value FROM device_config WHERE device_id = ? ORDER BY cfg_key",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> entries.put(rs.getString("cfg_key"), rs.getInt("cfg_value")),
                deviceId);
        return entries;
    }

    /**
     * 整体替换设备配置：删除全部旧键后写入新键集，不部分覆盖。
     */
    public void replaceAll(String deviceId, Map<String, Integer> entries) {
        jdbc.update("DELETE FROM device_config WHERE device_id = ?", deviceId);
        for (Map.Entry<String, Integer> entry : entries.entrySet()) {
            jdbc.update("INSERT INTO device_config (device_id, cfg_key, cfg_value) VALUES (?, ?, ?)",
                    deviceId, entry.getKey(), entry.getValue());
        }
    }
}
