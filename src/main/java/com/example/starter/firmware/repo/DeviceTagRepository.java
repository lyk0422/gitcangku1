package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 设备标签数据访问。标签集合以规范化字符串逐行存储，读取按 tag 字典序排序。
 * 标签替换（先删后插）必须在调用方事务内执行，失败整体回滚。
 */
@Repository
public class DeviceTagRepository {

    private final JdbcTemplate jdbc;

    public DeviceTagRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 单台设备的规范化标签集合，按字典序升序。
     */
    public List<String> findTags(String deviceId) {
        return jdbc.queryForList("SELECT tag FROM device_tag WHERE device_id = ? ORDER BY tag",
                String.class, deviceId);
    }

    /**
     * 指定型号下所有设备的标签，按设备ID分组、组内按 tag 字典序。
     */
    public Map<String, List<String>> findTagsByModel(String model) {
        Map<String, List<String>> result = new TreeMap<>();
        jdbc.query("SELECT t.device_id AS device_id, t.tag AS tag FROM device_tag t"
                        + " INNER JOIN device d ON d.device_id = t.device_id WHERE d.model = ?"
                        + " ORDER BY t.device_id, t.tag",
                rs -> {
                    result.computeIfAbsent(rs.getString("device_id"), k -> new java.util.ArrayList<>())
                            .add(rs.getString("tag"));
                },
                model);
        return result;
    }

    /**
     * 事务内全量替换设备标签：先清空再批量插入。
     */
    public void replaceTags(String deviceId, Collection<String> normalizedTags) {
        jdbc.update("DELETE FROM device_tag WHERE device_id = ?", deviceId);
        if (normalizedTags.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO device_tag (device_id, tag) VALUES (?, ?)",
                normalizedTags, normalizedTags.size(), (ps, tag) -> {
                    ps.setString(1, deviceId);
                    ps.setString(2, tag);
                });
    }
}
