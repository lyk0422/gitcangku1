package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Device;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备登记表数据访问。维护、领取、回执、取消通过 SELECT ... FOR UPDATE 共同锁定设备空间档案。
 */
@Repository
public class DeviceRepository {

    private static final RowMapper<Device> MAPPER = (rs, rowNum) -> new Device(
            rs.getString("device_id"), rs.getString("model"), rs.getString("current_version"),
            rs.getInt("bucket_no"), (Long) rs.getObject("total_space_bytes"),
            (Long) rs.getObject("current_image_bytes"), rs.getInt("space_version"));

    private static final String COLUMNS = "device_id, model, current_version, bucket_no,"
            + " total_space_bytes, current_image_bytes, space_version";

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Device device) {
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no,"
                        + " total_space_bytes, current_image_bytes, space_version) VALUES (?, ?, ?, ?, ?, ?, 1)",
                device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                device.totalSpaceBytes(), device.currentImageBytes());
    }

    public Optional<Device> findById(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ?", MAPPER, deviceId)
                .stream().findFirst();
    }

    /**
     * 锁定设备行（空间档案），调用方须在事务内；维护、领取、回执、取消共用此锁形成一致提交顺序。
     */
    public Optional<Device> findByIdForUpdate(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ? FOR UPDATE",
                MAPPER, deviceId).stream().findFirst();
    }

    /**
     * 任务 SUCCESS 终结：当前固件版本切换为新版本，空间档案版本加一；
     * 有暂存预留时当前镜像原子替换为新镜像字节数（旧镜像、压缩包及预留一并删除）。
     */
    public void completeSuccess(String deviceId, String newVersion, Long newImageBytes) {
        if (newImageBytes == null) {
            jdbc.update("UPDATE device SET current_version = ?, space_version = space_version + 1"
                    + " WHERE device_id = ?", newVersion, deviceId);
        } else {
            jdbc.update("UPDATE device SET current_version = ?, current_image_bytes = ?,"
                    + " space_version = space_version + 1 WHERE device_id = ?",
                    newVersion, newImageBytes, deviceId);
        }
    }

    /**
     * 任务 FAILED/CANCELLED 首次终结：仅空间档案版本加一，旧镜像与当前版本不变。
     */
    public void bumpSpaceVersion(String deviceId) {
        jdbc.update("UPDATE device SET space_version = space_version + 1 WHERE device_id = ?", deviceId);
    }

    /**
     * 维护空间档案：更新总空间与当前镜像字节数，版本加一。调用方已持设备行锁并完成校验。
     */
    public void maintainSpace(String deviceId, long totalSpaceBytes, long currentImageBytes) {
        jdbc.update("UPDATE device SET total_space_bytes = ?, current_image_bytes = ?,"
                        + " space_version = space_version + 1 WHERE device_id = ?",
                totalSpaceBytes, currentImageBytes, deviceId);
    }
}
