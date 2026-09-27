package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 证书链授权设备集合数据访问。集合为空表示链对适用设备全开放。
 */
@Repository
public class CertChainDeviceRepository {

    private final JdbcTemplate jdbc;

    public CertChainDeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void addDevice(long chainId, String deviceId) {
        jdbc.update("MERGE INTO cert_chain_device (chain_id, device_id) KEY (chain_id, device_id) VALUES (?, ?)",
                chainId, deviceId);
    }

    public List<String> findDeviceIds(long chainId) {
        return jdbc.queryForList(
                "SELECT device_id FROM cert_chain_device WHERE chain_id = ? ORDER BY id",
                String.class, chainId);
    }

    public long countDevices(long chainId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cert_chain_device WHERE chain_id = ?", Long.class, chainId);
        return count == null ? 0 : count;
    }

    public boolean exists(long chainId, String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cert_chain_device WHERE chain_id = ? AND device_id = ?",
                Long.class, chainId, deviceId);
        return count != null && count > 0;
    }
}
