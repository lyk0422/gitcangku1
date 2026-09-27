package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.FirmwareCompatibility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 固件兼容矩阵数据访问。同升级路径唯一；allowed=0 为显式不兼容。
 */
@Repository
public class CompatibilityRepository {

    private final JdbcTemplate jdbc;

    public CompatibilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(String model, String fromVersion, String toVersion, boolean allowed) {
        jdbc.update("MERGE INTO firmware_compatibility (model, from_version, to_version, allowed)"
                + " KEY (model, from_version, to_version) VALUES (?, ?, ?, ?)",
                model, fromVersion, toVersion, allowed ? 1 : 0);
    }

    public Optional<FirmwareCompatibility> find(String model, String fromVersion, String toVersion) {
        return jdbc.query(
                "SELECT id, model, from_version, to_version, allowed FROM firmware_compatibility"
                        + " WHERE model = ? AND from_version = ? AND to_version = ?",
                (rs, n) -> new FirmwareCompatibility(rs.getLong("id"), rs.getString("model"),
                        rs.getString("from_version"), rs.getString("to_version"),
                        rs.getInt("allowed") == 1),
                model, fromVersion, toVersion).stream().findFirst();
    }
}
