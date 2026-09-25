package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.FirmwareVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 固件版本链数据访问。登记与环校验在同一事务内完成，前置版本行加锁保证提交时刻一致。
 */
@Repository
public class VersionRepository {

    private static final RowMapper<FirmwareVersion> MAPPER = (rs, rowNum) -> new FirmwareVersion(
            rs.getString("version"), rs.getString("predecessor"));

    private final JdbcTemplate jdbc;

    public VersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String version, String predecessor) {
        jdbc.update("INSERT INTO firmware_version (version, predecessor) VALUES (?, ?)",
                version, predecessor);
    }

    public void updatePredecessor(String version, String predecessor) {
        jdbc.update("UPDATE firmware_version SET predecessor = ? WHERE version = ?",
                predecessor, version);
    }

    public Optional<FirmwareVersion> findByVersion(String version) {
        return jdbc.query("SELECT version, predecessor FROM firmware_version WHERE version = ?",
                MAPPER, version).stream().findFirst();
    }

    /**
     * 行锁读取：登记/修改前置链时锁定相关版本行，保证并发登记按提交顺序裁决。
     */
    public Optional<FirmwareVersion> findByVersionForUpdate(String version) {
        return jdbc.query("SELECT version, predecessor FROM firmware_version WHERE version = ? FOR UPDATE",
                MAPPER, version).stream().findFirst();
    }
}
