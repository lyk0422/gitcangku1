package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ExemptionEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 发布单模块豁免数据访问：每次修改整体替换为新版本，历史版本只增不改。
 */
@Repository
public class ExemptionRepository {

    private static final RowMapper<ExemptionEntry> MAPPER = (rs, rowNum) -> new ExemptionEntry(
            rs.getLong("id"), rs.getLong("release_id"), rs.getInt("exemption_version"),
            rs.getString("device_group"), rs.getString("module"),
            rs.getString("expires_at_utc"), rs.getString("created_at_utc"));

    private static final String COLUMNS = "id, release_id, exemption_version, device_group, module,"
            + " expires_at_utc, created_at_utc";

    private final JdbcTemplate jdbc;

    public ExemptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long releaseId, int exemptionVersion, String deviceGroup, String module,
                       String expiresAtUtc, String createdAtUtc) {
        jdbc.update("INSERT INTO release_exemption"
                        + " (release_id, exemption_version, device_group, module, expires_at_utc, created_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                releaseId, exemptionVersion, deviceGroup, module, expiresAtUtc, createdAtUtc);
    }

    /**
     * 指定豁免版本的全部豁免行，按（设备组，模块）升序，排序稳定。
     */
    public List<ExemptionEntry> findByReleaseAndVersion(long releaseId, int exemptionVersion) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_exemption"
                        + " WHERE release_id = ? AND exemption_version = ?"
                        + " ORDER BY device_group, module", MAPPER, releaseId, exemptionVersion);
    }

    /**
     * 发布单全部豁免历史，按（豁免版本，设备组，模块）升序。
     */
    public List<ExemptionEntry> findByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_exemption"
                + " WHERE release_id = ? ORDER BY exemption_version, device_group, module",
                MAPPER, releaseId);
    }
}
