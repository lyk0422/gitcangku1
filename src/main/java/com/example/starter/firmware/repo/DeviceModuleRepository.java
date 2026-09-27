package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ModuleSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 设备模块清单数据访问。设备上报为全量替换：先删后插，与上报业务在同一事务提交。
 */
@Repository
public class DeviceModuleRepository {

    private final JdbcTemplate jdbc;

    public DeviceModuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void deleteByDevice(String deviceId) {
        jdbc.update("DELETE FROM device_module WHERE device_id = ?", deviceId);
    }

    public void insert(String deviceId, String moduleName, String version, String digest, String reportedAtUtc) {
        jdbc.update("INSERT INTO device_module (device_id, module_name, version, digest, reported_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?)",
                deviceId, moduleName, version, digest, reportedAtUtc);
    }

    /**
     * 设备当前模块清单，按模块名排序返回。
     */
    public List<ModuleSnapshot> findSnapshotsByDevice(String deviceId) {
        return jdbc.query("SELECT module_name, version, digest FROM device_module"
                        + " WHERE device_id = ? ORDER BY module_name",
                (rs, n) -> new ModuleSnapshot(rs.getString("module_name"), rs.getString("version"),
                        rs.getString("digest")),
                deviceId);
    }

    /**
     * 设备最近一次上报时刻（UTC，ISO-8601）；从未上报返回 null。同设备清单同次写入，时刻一致。
     */
    public String findReportedAt(String deviceId) {
        List<String> values = jdbc.query("SELECT reported_at_utc FROM device_module WHERE device_id = ?",
                (rs, n) -> rs.getString("reported_at_utc"), deviceId);
        return values.isEmpty() ? null : values.get(0);
    }
}
