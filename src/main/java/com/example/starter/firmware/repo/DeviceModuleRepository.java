package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.DeviceModule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 设备模块清单数据访问。按设备全量替换：上报头版本加一，清单行先删后插。
 */
@Repository
public class DeviceModuleRepository {

    private final JdbcTemplate jdbc;

    public DeviceModuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 全量重报：版本加一（首次为 1），替换清单与上报时刻。调用方在事务内先完成参数校验。
     */
    public int replaceReport(String deviceId, List<DeviceModule> modules, String reportedAtUtc) {
        int updated = jdbc.update("UPDATE device_module_report SET report_version = report_version + 1,"
                + " reported_at_utc = ? WHERE device_id = ?", reportedAtUtc, deviceId);
        int version;
        if (updated == 0) {
            jdbc.update("INSERT INTO device_module_report (device_id, report_version, reported_at_utc)"
                    + " VALUES (?, 1, ?)", deviceId, reportedAtUtc);
            version = 1;
        } else {
            version = jdbc.queryForObject(
                    "SELECT report_version FROM device_module_report WHERE device_id = ?",
                    Integer.class, deviceId);
        }
        jdbc.update("DELETE FROM device_module WHERE device_id = ?", deviceId);
        jdbc.batchUpdate("INSERT INTO device_module (device_id, module_name, version) VALUES (?, ?, ?)",
                modules, modules.size(), (ps, m) -> {
                    ps.setString(1, deviceId);
                    ps.setString(2, m.moduleName());
                    ps.setString(3, m.version());
                });
        return version;
    }

    public List<DeviceModule> findByDevice(String deviceId) {
        return jdbc.query("SELECT device_id, module_name, version FROM device_module"
                + " WHERE device_id = ? ORDER BY module_name",
                (rs, n) -> new DeviceModule(rs.getString("module_name"), rs.getString("version")),
                deviceId);
    }

    public Integer findReportVersion(String deviceId) {
        return jdbc.query("SELECT report_version FROM device_module_report WHERE device_id = ?",
                (rs, n) -> rs.getInt("report_version"), deviceId).stream().findFirst().orElse(null);
    }

    public String findReportedAtUtc(String deviceId) {
        return jdbc.query("SELECT reported_at_utc FROM device_module_report WHERE device_id = ?",
                (rs, n) -> rs.getString("reported_at_utc"), deviceId).stream().findFirst().orElse(null);
    }
}
