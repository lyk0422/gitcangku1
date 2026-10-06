package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.DeviceBootState;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备启动分区状态数据访问。无记录时视为分区 A；正式切换时写入或翻转为目标分区。
 */
@Repository
public class DeviceBootStateRepository {

    private final JdbcTemplate jdbc;

    public DeviceBootStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DeviceBootState> findById(String deviceId) {
        return jdbc.query("SELECT device_id, current_partition FROM device_boot_state WHERE device_id = ?",
                (rs, rowNum) -> new DeviceBootState(rs.getString("device_id"), rs.getString("current_partition")),
                deviceId).stream().findFirst();
    }

    /**
     * 当前启动分区，无记录时返回默认分区 A。
     */
    public String currentPartition(String deviceId) {
        return findById(deviceId).map(DeviceBootState::currentPartition)
                .orElse(DeviceBootState.DEFAULT_PARTITION);
    }

    /**
     * 写入或翻转为指定分区；并发插入冲突时退化为更新。
     */
    public void upsert(String deviceId, String partition) {
        try {
            jdbc.update("INSERT INTO device_boot_state (device_id, current_partition) VALUES (?, ?)",
                    deviceId, partition);
        } catch (DuplicateKeyException e) {
            jdbc.update("UPDATE device_boot_state SET current_partition = ?,"
                    + " updated_at = CURRENT_TIMESTAMP WHERE device_id = ?", partition, deviceId);
        }
    }
}
