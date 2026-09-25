package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 试验中心数据访问；中心行级锁串行化激活、暂停、关闭与中心内分配并发。
 */
@Repository
public class SiteRepository {

    /**
     * 中心行。status 取值 PENDING/ACTIVE/SUSPENDED/CLOSED；
     * generation 为激活代次（0=从未激活）；pending_* 为等待第二人确认的首次激活确认。
     */
    public record SiteRow(
            String experimentId,
            String siteCode,
            String status,
            int targetCap,
            int generation,
            String pendingActivationKey,
            String pendingActor,
            Long pendingConfirmedAt,
            long createdAt,
            Long closedAt) {
    }

    private static final RowMapper<SiteRow> MAPPER = (rs, n) -> new SiteRow(
            rs.getString("experiment_id"),
            rs.getString("site_code"),
            rs.getString("status"),
            rs.getInt("target_cap"),
            rs.getInt("generation"),
            rs.getString("pending_activation_key"),
            rs.getString("pending_actor"),
            (Long) rs.getObject("pending_confirmed_at"),
            rs.getLong("created_at"),
            (Long) rs.getObject("closed_at"));

    private static final String COLUMNS =
            "experiment_id, site_code, status, target_cap, generation, "
                    + "pending_activation_key, pending_actor, pending_confirmed_at, "
                    + "created_at, closed_at";

    private final JdbcTemplate jdbc;

    public SiteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SiteRow row) {
        jdbc.update("INSERT INTO site ("
                        + "experiment_id, site_code, status, target_cap, generation, "
                        + "pending_activation_key, pending_actor, pending_confirmed_at, "
                        + "created_at, closed_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.siteCode(), row.status(), row.targetCap(), row.generation(),
                row.pendingActivationKey(), row.pendingActor(), row.pendingConfirmedAt(),
                row.createdAt(), row.closedAt());
    }

    public SiteRow findById(String experimentId, String siteCode) {
        List<SiteRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM site WHERE experiment_id = ? AND site_code = ?",
                MAPPER, experimentId, siteCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 行级锁定中心，串行化同中心的激活确认、暂停、关闭与分配并发；事务结束时释放。
     */
    public SiteRow lockById(String experimentId, String siteCode) {
        List<SiteRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM site WHERE experiment_id = ? AND site_code = ? FOR UPDATE",
                MAPPER, experimentId, siteCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 记录首次激活确认：仅当前无待确认时生效。
     *
     * @return 受影响行数；0 表示已有待确认或状态已变化
     */
    public int recordFirstConfirmation(String experimentId, String siteCode,
                                       String activationKey, String actor, long confirmedAt) {
        return jdbc.update("UPDATE site SET pending_activation_key = ?, pending_actor = ?, "
                        + "pending_confirmed_at = ? "
                        + "WHERE experiment_id = ? AND site_code = ? AND pending_actor IS NULL",
                activationKey, actor, confirmedAt, experimentId, siteCode);
    }

    /**
     * 激活生效：置 ACTIVE、代次 +1 并清空待确认；仅 PENDING/SUSPENDED 可激活。
     *
     * @return 受影响行数；0 表示状态不允许激活
     */
    public int markActive(String experimentId, String siteCode, int newGeneration) {
        return jdbc.update("UPDATE site SET status = 'ACTIVE', generation = ?, "
                        + "pending_activation_key = NULL, pending_actor = NULL, "
                        + "pending_confirmed_at = NULL "
                        + "WHERE experiment_id = ? AND site_code = ? AND status IN ('PENDING', 'SUSPENDED')",
                newGeneration, experimentId, siteCode);
    }

    /**
     * 暂停：仅 ACTIVE -> SUSPENDED；既有受试者盲态、区组容量与揭盲权限不变。
     *
     * @return 受影响行数；0 表示不存在或非 ACTIVE
     */
    public int markSuspended(String experimentId, String siteCode) {
        return jdbc.update("UPDATE site SET status = 'SUSPENDED' "
                        + "WHERE experiment_id = ? AND site_code = ? AND status = 'ACTIVE'",
                experimentId, siteCode);
    }

    /**
     * 关闭：终态，不可恢复；同时清空待确认。
     *
     * @return 受影响行数；0 表示不存在或已关闭
     */
    public int markClosed(String experimentId, String siteCode, long closedAt) {
        return jdbc.update("UPDATE site SET status = 'CLOSED', closed_at = ?, "
                        + "pending_activation_key = NULL, pending_actor = NULL, "
                        + "pending_confirmed_at = NULL "
                        + "WHERE experiment_id = ? AND site_code = ? AND status <> 'CLOSED'",
                closedAt, experimentId, siteCode);
    }
}
