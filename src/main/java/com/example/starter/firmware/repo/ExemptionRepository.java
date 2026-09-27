package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ModuleExemption;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 发布单模块豁免数据访问。豁免整体版本化：每次修改写入新版本全量条目，历史版本保留供任务证据回溯。
 * 同一发布单豁免修改与拉取共用发布单行锁，按提交顺序裁决。
 */
@Repository
public class ExemptionRepository {

    private static final RowMapper<ModuleExemption> MAPPER = (rs, n) -> new ModuleExemption(
            rs.getLong("id"), rs.getLong("release_id"), rs.getInt("version"),
            rs.getString("device_group"), rs.getString("module_name"),
            rs.getString("expires_at_utc"), rs.getString("created_at_utc"));

    private static final String COLUMNS = "id, release_id, version, device_group, module_name,"
            + " expires_at_utc, created_at_utc";

    private final JdbcTemplate jdbc;

    public ExemptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入某豁免版本的一条目，调用方保证 (releaseId, version) 此前不存在。
     */
    public void insertEntry(long releaseId, int version, String deviceGroup, String moduleName,
                            String expiresAtUtc, String createdAtUtc) {
        jdbc.update("INSERT INTO module_exemption"
                        + " (release_id, version, device_group, module_name, expires_at_utc, created_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                releaseId, version, deviceGroup, moduleName, expiresAtUtc, createdAtUtc);
    }

    /**
     * 指定版本的全部豁免条目（历史快照）。
     */
    public List<ModuleExemption> findByVersion(long releaseId, int version) {
        return jdbc.query("SELECT " + COLUMNS + " FROM module_exemption"
                        + " WHERE release_id = ? AND version = ? ORDER BY device_group, module_name",
                MAPPER, releaseId, version);
    }

    /**
     * 最新版本的全部豁免条目（含已到期条目，到期判定在业务层按当前 UTC 时刻完成）。
     */
    public List<ModuleExemption> findLatest(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM module_exemption"
                        + " WHERE release_id = ? AND version = (SELECT COALESCE(MAX(version), 0)"
                        + " FROM module_exemption WHERE release_id = ?)"
                        + " ORDER BY device_group, module_name",
                MAPPER, releaseId, releaseId);
    }
}
