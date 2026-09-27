package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 设备规范化标签集合数据访问。
 */
@Repository
public class DeviceTagRepository {

    private final JdbcTemplate jdbc;

    public DeviceTagRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String deviceId, String tag) {
        jdbc.update("INSERT INTO device_tag (device_id, tag) VALUES (?, ?)", deviceId, tag);
    }

    public void delete(String deviceId, String tag) {
        jdbc.update("DELETE FROM device_tag WHERE device_id = ? AND tag = ?", deviceId, tag);
    }

    /**
     * 设备当前规范化标签集合（升序）。
     */
    public List<String> findByDevice(String deviceId) {
        return jdbc.query("SELECT tag FROM device_tag WHERE device_id = ? ORDER BY tag",
                (rs, rowNum) -> rs.getString("tag"), deviceId);
    }

    /**
     * 指定型号全部设备的标签集合：device_id -> 规范化标签集合。无标签设备映射为空集合。
     */
    public Map<String, Set<String>> findByModel(String model) {
        Map<String, Set<String>> result = new HashMap<>();
        jdbc.query("SELECT d.device_id, t.tag FROM device d"
                        + " LEFT JOIN device_tag t ON t.device_id = d.device_id"
                        + " WHERE d.model = ? ORDER BY d.device_id, t.tag",
                (RowCallbackHandler) rs -> {
                    Set<String> tags = result.computeIfAbsent(rs.getString("device_id"),
                            k -> new TreeSet<>());
                    String tag = rs.getString("tag");
                    if (tag != null) {
                        tags.add(tag);
                    }
                }, model);
        return result;
    }
}
