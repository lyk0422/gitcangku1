package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.DeviceModuleEntry;
import com.example.starter.firmware.domain.DeviceModuleReport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 设备模块清单数据访问：当前清单每次上报整体替换，上报历史只增不改。
 */
@Repository
public class DeviceModuleRepository {

    private static final RowMapper<DeviceModuleEntry> ENTRY_MAPPER = (rs, rowNum) ->
            new DeviceModuleEntry(rs.getString("module"), rs.getString("version"));

    private static final RowMapper<DeviceModuleReport> REPORT_MAPPER = (rs, rowNum) ->
            new DeviceModuleReport(rs.getInt("report_version"), rs.getString("module_digest"),
                    rs.getString("reported_at_utc"));

    private final JdbcTemplate jdbc;

    public DeviceModuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 设备当前模块清单，按模块名升序，排序稳定。
     */
    public List<DeviceModuleEntry> findByDevice(String deviceId) {
        return jdbc.query("SELECT module, version FROM device_module WHERE device_id = ?"
                + " ORDER BY module", ENTRY_MAPPER, deviceId);
    }

    /**
     * 整体替换设备模块清单：先清空再写入本次上报内容，须在事务内与摘要更新一起提交。
     */
    public void replaceAll(String deviceId, List<DeviceModuleEntry> modules) {
        jdbc.update("DELETE FROM device_module WHERE device_id = ?", deviceId);
        for (DeviceModuleEntry entry : modules) {
            jdbc.update("INSERT INTO device_module (device_id, module, version) VALUES (?, ?, ?)",
                    deviceId, entry.module(), entry.version());
        }
    }

    public void insertReport(String deviceId, DeviceModuleReport report) {
        jdbc.update("INSERT INTO device_module_report"
                        + " (device_id, report_version, module_digest, reported_at_utc)"
                        + " VALUES (?, ?, ?, ?)",
                deviceId, report.reportVersion(), report.moduleDigest(), report.reportedAtUtc());
    }

    /**
     * 上报历史，按上报版本升序。
     */
    public List<DeviceModuleReport> findReportsByDevice(String deviceId) {
        return jdbc.query("SELECT report_version, module_digest, reported_at_utc"
                + " FROM device_module_report WHERE device_id = ? ORDER BY report_version",
                REPORT_MAPPER, deviceId);
    }
}
