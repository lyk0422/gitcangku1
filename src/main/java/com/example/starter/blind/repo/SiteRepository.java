package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 研究中心数据访问；中心行锁串行化启用与分配并发。
 */
@Repository
public class SiteRepository {

    /** 中心行。activeVersion 为 null 表示从未启用；activatedAt 为 UTC 毫秒。 */
    public record SiteRow(
            String id,
            String leadActorId,
            String status,
            Integer activeVersion,
            long createdAt,
            Long activatedAt) {
    }

    private static final RowMapper<SiteRow> MAPPER = (rs, n) -> new SiteRow(
            rs.getString("id"),
            rs.getString("lead_actor_id"),
            rs.getString("status"),
            (Integer) rs.getObject("active_version"),
            rs.getLong("created_at"),
            (Long) rs.getObject("activated_at"));

    private static final String COLUMNS =
            "id, lead_actor_id, status, active_version, created_at, activated_at";

    private final JdbcTemplate jdbc;

    public SiteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SiteRow row) {
        jdbc.update("INSERT INTO site (id, lead_actor_id, status, active_version, created_at, activated_at) "
                        + "VALUES (?, ?, 'INACTIVE', NULL, ?, NULL)",
                row.id(), row.leadActorId(), row.createdAt());
    }

    public SiteRow findById(String siteId) {
        List<SiteRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM site WHERE id = ?", MAPPER, siteId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定中心，串行化启用提交与分配；事务结束释放。 */
    public SiteRow lockById(String siteId) {
        List<SiteRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM site WHERE id = ? FOR UPDATE", MAPPER, siteId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 一次性启用：仅 INACTIVE 可激活；已激活中心的后续版本复用独立的版本切换更新。
     *
     * @return 受影响行数；0 表示中心不存在或已启用
     */
    public int markActivated(String siteId, int version, long activatedAt) {
        return jdbc.update(
                "UPDATE site SET status = 'ACTIVE', active_version = ?, activated_at = ? "
                        + "WHERE id = ? AND status = 'INACTIVE'",
                version, activatedAt, siteId);
    }

    /**
     * 已启用中心切换到新冻结版本；仅当当前版本小于新版本时生效，防止乱序/并发回退。
     *
     * @return 受影响行数；0 表示版本非递增或中心不存在
     */
    public int switchActiveVersion(String siteId, int newVersion, long activatedAt) {
        return jdbc.update(
                "UPDATE site SET active_version = ?, activated_at = ? "
                        + "WHERE id = ? AND status = 'ACTIVE' AND (active_version IS NULL OR active_version < ?)",
                newVersion, activatedAt, siteId, newVersion);
    }
}
