package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 设备信任集合数据访问：设备对某固件版本某证书链版本的信任关系。
 * 唯一约束 uk_device_trust_chain 保证重复授权不产生重复行。
 */
@Repository
public class DeviceTrustRepository {

    private final JdbcTemplate jdbc;

    public DeviceTrustRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 添加信任关系；已存在时幂等忽略，返回是否新插入。
     */
    public int addIfAbsent(String deviceId, String model, String firmwareVersion,
                           int chainVersion, String operator) {
        return jdbc.update("INSERT INTO device_trust (device_id, model, firmware_version, chain_version, created_by)"
                + " SELECT ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM device_trust"
                + " WHERE device_id = ? AND model = ? AND firmware_version = ? AND chain_version = ?)",
                deviceId, model, firmwareVersion, chainVersion, operator,
                deviceId, model, firmwareVersion, chainVersion);
    }

    public boolean exists(String deviceId, String model, String firmwareVersion, int chainVersion) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM device_trust"
                + " WHERE device_id = ? AND model = ? AND firmware_version = ? AND chain_version = ?",
                Long.class, deviceId, model, firmwareVersion, chainVersion);
        return count != null && count > 0;
    }

    public long countByChain(String model, String firmwareVersion, int chainVersion) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM device_trust"
                + " WHERE model = ? AND firmware_version = ? AND chain_version = ?",
                Long.class, model, firmwareVersion, chainVersion);
        return count == null ? 0 : count;
    }
}
