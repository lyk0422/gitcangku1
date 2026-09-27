package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.FirmwareVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Optional;

/**
 * 固件版本登记数据访问。(model, version) 唯一约束保证同型号版本号唯一。
 */
@Repository
public class FirmwareVersionRepository {

    private final JdbcTemplate jdbc;

    public FirmwareVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String model, String version, String packageUri) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO firmware_version (model, version, package_uri) VALUES (?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, model);
            ps.setString(2, version);
            ps.setString(3, packageUri);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<FirmwareVersion> findById(long id) {
        return jdbc.query("SELECT id, model, version, package_uri FROM firmware_version WHERE id = ?",
                (rs, n) -> new FirmwareVersion(rs.getLong("id"), rs.getString("model"),
                        rs.getString("version"), rs.getString("package_uri")),
                id).stream().findFirst();
    }

    public Optional<FirmwareVersion> findByModelVersion(String model, String version) {
        return jdbc.query(
                "SELECT id, model, version, package_uri FROM firmware_version WHERE model = ? AND version = ?",
                (rs, n) -> new FirmwareVersion(rs.getLong("id"), rs.getString("model"),
                        rs.getString("version"), rs.getString("package_uri")),
                model, version).stream().findFirst();
    }
}
