package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备登记表数据访问。维护、领取、回执和取消通过 findByIdForUpdate 共同锁定设备空间行，
 * 空间档案版本（space_version）只在持有该行锁的事务内加一。
 */
@Repository
public class DeviceRepository {

    private static final RowMapper<Device> MAPPER = (rs, rowNum) -> new Device(
            rs.getString("device_id"), rs.getString("model"),
            rs.getString("current_version"), rs.getInt("bucket_no"),
            rs.getObject("total_space_bytes", Long.class),
            rs.getObject("current_image_bytes", Long.class),
            rs.getObject("space_version", Integer.class));

    private static final String COLUMNS = "device_id, model, current_version, bucket_no,"
            + " total_space_bytes, current_image_bytes, space_version";

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Device device) {
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no,"
                        + " total_space_bytes, current_image_bytes, space_version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                device.totalSpaceBytes(), device.currentImageBytes(), device.spaceVersion());
    }

    public Optional<Device> findById(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ?", MAPPER, deviceId)
                .stream().findFirst();
    }

    /**
     * 设备空间行锁：维护、领取、回执和取消共用，形成一致提交顺序。
     */
    public Optional<Device> findByIdForUpdate(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ? FOR UPDATE",
                MAPPER, deviceId).stream().findFirst();
    }

    /**
     * 旧模式 SUCCESS 回执：仅更新当前固件版本，不涉及空间档案。
     */
    public void updateCurrentVersion(String deviceId, String newVersion) {
        jdbc.update("UPDATE device SET current_version = ? WHERE device_id = ?", newVersion, deviceId);
    }

    /**
     * 空间维护：调整总空间并空间档案版本加一，调用方必须已持有设备行锁。
     */
    public void updateSpace(String deviceId, long totalSpaceBytes) {
        jdbc.update("UPDATE device SET total_space_bytes = ?, space_version = space_version + 1"
                + " WHERE device_id = ?", totalSpaceBytes, deviceId);
    }

    /**
     * SUCCESS 首次终结：原子删除旧镜像、压缩包及预留，当前镜像改为新字节数并沿用版本更新，
     * 空间档案版本加一。调用方必须已持有设备行锁。
     */
    public void completeStagingSuccess(String deviceId, String newVersion, long newImageBytes) {
        jdbc.update("UPDATE device SET current_version = ?, current_image_bytes = ?,"
                + " space_version = space_version + 1 WHERE device_id = ?",
                newVersion, newImageBytes, deviceId);
    }

    /**
     * FAILED 或 CANCELLED 首次终结：只释放包与新镜像预留，旧镜像不变，空间档案版本加一。
     * 调用方必须已持有设备行锁。
     */
    public void releaseStaging(String deviceId) {
        jdbc.update("UPDATE device SET space_version = space_version + 1 WHERE device_id = ?", deviceId);
    }
}
