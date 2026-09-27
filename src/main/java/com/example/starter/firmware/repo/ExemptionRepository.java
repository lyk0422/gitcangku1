package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 模块豁免数据访问。当前态按 (发布单, 设备组) 唯一，历史只增不改；
 * 修改时先关闭上一版本（superseded_at_utc），再插入新版本，全程在同一事务内。
 */
@Repository
public class ExemptionRepository {

    public record ExemptionRow(long releaseId, String groupName, int version, String modulesJson,
                               String expiresAtUtc, String createdAtUtc) {
    }

    public record ExemptionHistoryRow(long id, long releaseId, String groupName, int version,
                                      String modulesJson, String expiresAtUtc,
                                      String createdAtUtc, String supersededAtUtc) {
    }

    private static final org.springframework.jdbc.core.RowMapper<ExemptionRow> ROW_MAPPER = (rs, n) ->
            new ExemptionRow(rs.getLong("release_id"), rs.getString("group_name"), rs.getInt("version"),
                    rs.getString("modules"), rs.getString("expires_at_utc"), rs.getString("created_at_utc"));

    private static final org.springframework.jdbc.core.RowMapper<ExemptionHistoryRow> HISTORY_MAPPER = (rs, n) ->
            new ExemptionHistoryRow(rs.getLong("id"), rs.getLong("release_id"), rs.getString("group_name"),
                    rs.getInt("version"), rs.getString("modules"), rs.getString("expires_at_utc"),
                    rs.getString("created_at_utc"), rs.getString("superseded_at_utc"));

    private final JdbcTemplate jdbc;

    public ExemptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ExemptionRow> findCurrent(long releaseId, String groupName) {
        return jdbc.query("SELECT release_id, group_name, version, modules, expires_at_utc, created_at_utc"
                        + " FROM module_exemption WHERE release_id = ? AND group_name = ?",
                ROW_MAPPER, releaseId, groupName).stream().findFirst();
    }

    public Optional<ExemptionRow> findCurrentForUpdate(long releaseId, String groupName) {
        return jdbc.query("SELECT release_id, group_name, version, modules, expires_at_utc, created_at_utc"
                        + " FROM module_exemption WHERE release_id = ? AND group_name = ? FOR UPDATE",
                ROW_MAPPER, releaseId, groupName).stream().findFirst();
    }

    public List<ExemptionRow> findByRelease(long releaseId) {
        return jdbc.query("SELECT release_id, group_name, version, modules, expires_at_utc, created_at_utc"
                + " FROM module_exemption WHERE release_id = ? ORDER BY group_name", ROW_MAPPER, releaseId);
    }

    /**
     * 写入首个豁免版本：当前态与历史各一行。
     */
    public void insertFirst(long releaseId, String groupName, String modulesJson,
                            String expiresAtUtc, String createdAtUtc) {
        jdbc.update("INSERT INTO module_exemption (release_id, group_name, version, modules,"
                + " expires_at_utc, created_at_utc) VALUES (?, ?, 1, ?, ?, ?)",
                releaseId, groupName, modulesJson, expiresAtUtc, createdAtUtc);
        jdbc.update("INSERT INTO module_exemption_history (release_id, group_name, version, modules,"
                + " expires_at_utc, created_at_utc, superseded_at_utc) VALUES (?, ?, 1, ?, ?, ?, NULL)",
                releaseId, groupName, modulesJson, expiresAtUtc, createdAtUtc);
    }

    /**
     * 修订豁免：关闭旧版本历史、更新当前态版本号并写新历史行。调用方已持有当前态行锁。
     */
    public void revise(long releaseId, String groupName, int currentVersion, String modulesJson,
                       String expiresAtUtc, String nowUtc) {
        jdbc.update("UPDATE module_exemption_history SET superseded_at_utc = ?"
                + " WHERE release_id = ? AND group_name = ? AND version = ? AND superseded_at_utc IS NULL",
                nowUtc, releaseId, groupName, currentVersion);
        jdbc.update("UPDATE module_exemption SET version = ?, modules = ?, expires_at_utc = ?,"
                + " created_at_utc = ? WHERE release_id = ? AND group_name = ?",
                currentVersion + 1, modulesJson, expiresAtUtc, nowUtc, releaseId, groupName);
        jdbc.update("INSERT INTO module_exemption_history (release_id, group_name, version, modules,"
                + " expires_at_utc, created_at_utc, superseded_at_utc) VALUES (?, ?, ?, ?, ?, ?, NULL)",
                releaseId, groupName, currentVersion + 1, modulesJson, expiresAtUtc, nowUtc);
    }

    public List<ExemptionHistoryRow> findHistory(long releaseId, String groupName) {
        return jdbc.query("SELECT id, release_id, group_name, version, modules, expires_at_utc,"
                + " created_at_utc, superseded_at_utc FROM module_exemption_history"
                + " WHERE release_id = ? AND group_name = ? ORDER BY version",
                HISTORY_MAPPER, releaseId, groupName);
    }
}
