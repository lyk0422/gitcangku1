package com.example.starter.firmware.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 设备信任集合数据访问。(device_id, chain_id) 主键保证同一授权只落一行。
 */
@Repository
public class DeviceTrustRepository {

    private final JdbcTemplate jdbc;

    public DeviceTrustRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 授权设备信任某证书链；已存在时忽略（重复授权幂等）。
     */
    public void grant(String deviceId, long chainId) {
        try {
            jdbc.update("INSERT INTO device_trust (device_id, chain_id) VALUES (?, ?)", deviceId, chainId);
        } catch (DuplicateKeyException e) {
            // 重复授权：信任集合语义下幂等
        }
    }

    public boolean exists(String deviceId, long chainId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_trust WHERE device_id = ? AND chain_id = ?",
                Long.class, deviceId, chainId);
        return count != null && count > 0;
    }

    /**
     * 设备信任的全部证书链ID，按链ID升序。
     */
    public List<Long> findChainIdsByDevice(String deviceId) {
        return jdbc.queryForList(
                "SELECT chain_id FROM device_trust WHERE device_id = ? ORDER BY chain_id",
                Long.class, deviceId);
    }
}
