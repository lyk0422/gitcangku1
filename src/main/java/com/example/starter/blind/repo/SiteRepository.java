package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 研究中心、配置版本、启用快照与当前名额的数据访问；本类不涉及任何处理映射数据。
 */
@Repository
public class SiteRepository {

    /** 中心行。status 取值 INACTIVE/ACTIVE；activeVersion 未启用为 null。 */
    public record SiteRow(
            String experimentId,
            String siteId,
            String status,
            Integer activeVersion,
            long createdAt,
            Long activatedAt) {
    }

    /** 配置版本行。 */
    public record ConfigVersionRow(
            String experimentId,
            String siteId,
            int version,
            String createdBy,
            long createdAt) {
    }

    /** 配置版本/快照/当前名额共用的区组名额行。 */
    public record BlockRow(String experimentId, String siteId, int version,
                           String blockCode, int quota) {
    }

    /** 已启用中心的区组快照行；frozenQuota 为启用冻结下限，quota 为当前生效名额。 */
    public record SiteBlockRow(String experimentId, String siteId, String blockCode,
                               int frozenQuota, int quota) {
    }

    private static final RowMapper<SiteRow> SITE_MAPPER = (rs, n) -> new SiteRow(
            rs.getString("experiment_id"),
            rs.getString("site_id"),
            rs.getString("status"),
            (Integer) rs.getObject("active_version"),
            rs.getLong("created_at"),
            (Long) rs.getObject("activated_at"));

    private static final RowMapper<ConfigVersionRow> CONFIG_VERSION_MAPPER = (rs, n) ->
            new ConfigVersionRow(
                    rs.getString("experiment_id"),
                    rs.getString("site_id"),
                    rs.getInt("version"),
                    rs.getString("created_by"),
                    rs.getLong("created_at"));

    private static final RowMapper<BlockRow> CONFIG_BLOCK_MAPPER = (rs, n) -> new BlockRow(
            rs.getString("experiment_id"),
            rs.getString("site_id"),
            rs.getInt("version"),
            rs.getString("block_code"),
            rs.getInt("quota"));

    private static final RowMapper<SiteBlockRow> SITE_BLOCK_MAPPER = (rs, n) -> new SiteBlockRow(
            rs.getString("experiment_id"),
            rs.getString("site_id"),
            rs.getString("block_code"),
            rs.getInt("frozen_quota"),
            rs.getInt("quota"));

    private final JdbcTemplate jdbc;

    public SiteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertSite(SiteRow row) {
        jdbc.update("INSERT INTO site (experiment_id, site_id, status, active_version, "
                        + "created_at, activated_at) VALUES (?, ?, 'INACTIVE', NULL, ?, NULL)",
                row.experimentId(), row.siteId(), row.createdAt());
    }

    public SiteRow findSite(String experimentId, String siteId) {
        List<SiteRow> rows = jdbc.query(
                "SELECT experiment_id, site_id, status, active_version, created_at, activated_at "
                        + "FROM site WHERE experiment_id = ? AND site_id = ?",
                SITE_MAPPER, experimentId, siteId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定中心，串行化配置提交、启用与分配的并发。 */
    public SiteRow lockSite(String experimentId, String siteId) {
        List<SiteRow> rows = jdbc.query(
                "SELECT experiment_id, site_id, status, active_version, created_at, activated_at "
                        + "FROM site WHERE experiment_id = ? AND site_id = ? FOR UPDATE",
                SITE_MAPPER, experimentId, siteId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 启用提交：INACTIVE -> ACTIVE 并写入启用版本与时间；返回受影响行数。 */
    public int markActive(String experimentId, String siteId, int version, long activatedAt) {
        return jdbc.update("UPDATE site SET status = 'ACTIVE', active_version = ?, activated_at = ? "
                        + "WHERE experiment_id = ? AND site_id = ? AND status = 'INACTIVE'",
                version, activatedAt, experimentId, siteId);
    }

    public int insertConfigVersion(ConfigVersionRow row) {
        return jdbc.update("INSERT INTO site_config_version "
                        + "(experiment_id, site_id, version, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                row.experimentId(), row.siteId(), row.version(), row.createdBy(), row.createdAt());
    }

    public ConfigVersionRow findConfigVersion(String experimentId, String siteId, int version) {
        List<ConfigVersionRow> rows = jdbc.query(
                "SELECT experiment_id, site_id, version, created_by, created_at "
                        + "FROM site_config_version WHERE experiment_id = ? AND site_id = ? AND version = ?",
                CONFIG_VERSION_MAPPER, experimentId, siteId, version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Integer findMaxConfigVersion(String experimentId, String siteId) {
        List<Integer> versions = jdbc.queryForList(
                "SELECT MAX(version) FROM site_config_version "
                        + "WHERE experiment_id = ? AND site_id = ?",
                Integer.class, experimentId, siteId);
        return versions.isEmpty() ? null : versions.get(0);
    }

    public void insertConfigBlock(BlockRow row) {
        jdbc.update("INSERT INTO site_config_block "
                        + "(experiment_id, site_id, version, block_code, quota) "
                        + "VALUES (?, ?, ?, ?, ?)",
                row.experimentId(), row.siteId(), row.version(), row.blockCode(), row.quota());
    }

    public List<BlockRow> findConfigBlocks(String experimentId, String siteId, int version) {
        return jdbc.query(
                "SELECT experiment_id, site_id, version, block_code, quota "
                        + "FROM site_config_block WHERE experiment_id = ? AND site_id = ? AND version = ? "
                        + "ORDER BY block_code",
                CONFIG_BLOCK_MAPPER, experimentId, siteId, version);
    }

    /** 启用提交时批量写入启用快照（冻结下限 = 当前生效名额）。 */
    public void insertSiteBlocks(String experimentId, String siteId,
                                 List<BlockRow> frozenBlocks) {
        jdbc.batchUpdate("INSERT INTO site_block "
                        + "(experiment_id, site_id, block_code, frozen_quota, quota) "
                        + "VALUES (?, ?, ?, ?, ?)",
                frozenBlocks, frozenBlocks.size(), (ps, row) -> {
                    ps.setString(1, row.experimentId());
                    ps.setString(2, row.siteId());
                    ps.setString(3, row.blockCode());
                    ps.setInt(4, row.quota());
                    ps.setInt(5, row.quota());
                });
    }

    public List<SiteBlockRow> findSiteBlocks(String experimentId, String siteId) {
        return jdbc.query(
                "SELECT experiment_id, site_id, block_code, frozen_quota, quota "
                        + "FROM site_block WHERE experiment_id = ? AND site_id = ? "
                        + "ORDER BY block_code",
                SITE_BLOCK_MAPPER, experimentId, siteId);
    }

    public SiteBlockRow lockSiteBlock(String experimentId, String siteId, String blockCode) {
        List<SiteBlockRow> rows = jdbc.query(
                "SELECT experiment_id, site_id, block_code, frozen_quota, quota "
                        + "FROM site_block WHERE experiment_id = ? AND site_id = ? "
                        + "AND block_code = ? FOR UPDATE",
                SITE_BLOCK_MAPPER, experimentId, siteId, blockCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 调整启用后区组名额：仅当新名额不低于启用冻结量时生效；已分配量由调用方校验。
     *
     * @return 受影响行数；0 表示中心/区组不存在或新名额低于冻结量
     */
    public int updateQuota(String experimentId, String siteId, String blockCode, int newQuota) {
        return jdbc.update("UPDATE site_block SET quota = ? "
                        + "WHERE experiment_id = ? AND site_id = ? AND block_code = ? "
                        + "AND ? >= frozen_quota",
                newQuota, experimentId, siteId, blockCode, newQuota);
    }
}
