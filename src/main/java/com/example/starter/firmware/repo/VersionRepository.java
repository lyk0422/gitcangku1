package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.FirmwareVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 固件版本链数据访问。版本与其前置链接登记后不可修改，链结构只增不改。
 */
@Repository
public class VersionRepository {

    private final JdbcTemplate jdbc;

    public VersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String version, String predecessorVersion) {
        jdbc.update("INSERT INTO firmware_version (version, predecessor_version) VALUES (?, ?)",
                version, predecessorVersion);
    }

    public Optional<FirmwareVersion> find(String version) {
        return jdbc.query("SELECT version, predecessor_version FROM firmware_version WHERE version = ?",
                (rs, rowNum) -> new FirmwareVersion(rs.getString("version"),
                        rs.getString("predecessor_version")),
                version).stream().findFirst();
    }
}
