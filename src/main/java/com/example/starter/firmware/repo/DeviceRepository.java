package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备登记表数据访问。
 */
@Repository
public class DeviceRepository {

    private static final String COLUMNS = "device_id, model, current_version, bucket_no,"
            + " device_group, module_digest, module_report_version";

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Device device) {
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no, device_group)"
                        + " VALUES (?, ?, ?, ?, ?)",
                device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                device.deviceGroup());
    }

    public Optional<Device> findById(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ?",
                (rs, rowNum) -> new Device(rs.getString("device_id"), rs.getString("model"),
                        rs.getString("current_version"), rs.getInt("bucket_no"),
                        rs.getString("device_group"), rs.getString("module_digest"),
                        rs.getInt("module_report_version")),
                deviceId).stream().findFirst();
    }

    /**
     * 设备行锁（SELECT ... FOR UPDATE）：拉取在发布单行锁之后获取，
     * 与模块上报的设备行更新互斥，保证固化的模块摘要与评估用清单来自同一上报版本。
     */
    public Optional<Device> findByIdForUpdate(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ? FOR UPDATE",
                (rs, rowNum) -> new Device(rs.getString("device_id"), rs.getString("model"),
                        rs.getString("current_version"), rs.getInt("bucket_no"),
                        rs.getString("device_group"), rs.getString("module_digest"),
                        rs.getInt("module_report_version")),
                deviceId).stream().findFirst();
    }

    public void updateCurrentVersion(String deviceId, String newVersion) {
        jdbc.update("UPDATE device SET current_version = ? WHERE device_id = ?", newVersion, deviceId);
    }

    /**
     * 模块上报成功后更新摘要与上报版本；不触碰固件版本，模块升级只以设备实际上报为准。
     */
    public void updateModuleReport(String deviceId, String moduleDigest, int reportVersion) {
        jdbc.update("UPDATE device SET module_digest = ?, module_report_version = ? WHERE device_id = ?",
                moduleDigest, reportVersion, deviceId);
    }
}
