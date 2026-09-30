package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 中心启用申请与版本名额、冻结快照数据访问；历史版本记录稳定保留。
 */
@Repository
public class SiteActivationRepository {

    /** 启用申请（配置版本）行。 */
    public record ActivationRow(
            String id,
            String siteId,
            int version,
            String status,
            String leadActorId,
            boolean leadConfirmed,
            Long leadConfirmedAt,
            String reviewerActorId,
            boolean reviewerConfirmed,
            Long reviewerConfirmedAt,
            long createdAt,
            Long activatedAt) {
    }

    /** 版本区组名额行。 */
    public record QuotaRow(String requestId, String siteId, int version, int blockNo, int quota) {
    }

    /** 已冻结快照区组行。 */
    public record SnapshotRow(String siteId, int version, int blockNo, int frozenQuota, long createdAt) {
    }

    private static final RowMapper<ActivationRow> ACTIVATION_MAPPER = (rs, n) -> new ActivationRow(
            rs.getString("id"),
            rs.getString("site_id"),
            rs.getInt("version"),
            rs.getString("status"),
            rs.getString("lead_actor_id"),
            rs.getBoolean("lead_confirmed"),
            (Long) rs.getObject("lead_confirmed_at"),
            rs.getString("reviewer_actor_id"),
            rs.getBoolean("reviewer_confirmed"),
            (Long) rs.getObject("reviewer_confirmed_at"),
            rs.getLong("created_at"),
            (Long) rs.getObject("activated_at"));

    private static final String ACTIVATION_COLUMNS =
            "id, site_id, version, status, lead_actor_id, lead_confirmed, lead_confirmed_at, "
                    + "reviewer_actor_id, reviewer_confirmed, reviewer_confirmed_at, "
                    + "created_at, activated_at";

    private static final RowMapper<QuotaRow> QUOTA_MAPPER = (rs, n) -> new QuotaRow(
            rs.getString("request_id"),
            rs.getString("site_id"),
            rs.getInt("version"),
            rs.getInt("block_no"),
            rs.getInt("quota"));

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, n) -> new SnapshotRow(
            rs.getString("site_id"),
            rs.getInt("version"),
            rs.getInt("block_no"),
            rs.getInt("frozen_quota"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public SiteActivationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertActivation(ActivationRow row) {
        jdbc.update("INSERT INTO site_activation_request ("
                        + "id, site_id, version, status, lead_actor_id, lead_confirmed, "
                        + "lead_confirmed_at, reviewer_actor_id, reviewer_confirmed, "
                        + "reviewer_confirmed_at, created_at, activated_at"
                        + ") VALUES (?, ?, ?, 'PENDING', ?, FALSE, NULL, NULL, FALSE, NULL, ?, NULL)",
                row.id(), row.siteId(), row.version(), row.leadActorId(), row.createdAt());
    }

    public void insertQuota(QuotaRow row) {
        jdbc.update("INSERT INTO site_block_quota (request_id, site_id, version, block_no, quota) "
                        + "VALUES (?, ?, ?, ?, ?)",
                row.requestId(), row.siteId(), row.version(), row.blockNo(), row.quota());
    }

    public ActivationRow findById(String requestId) {
        List<ActivationRow> rows = jdbc.query(
                "SELECT " + ACTIVATION_COLUMNS + " FROM site_activation_request WHERE id = ?",
                ACTIVATION_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定申请版本，串行化双人确认；事务结束释放。 */
    public ActivationRow lockById(String requestId) {
        List<ActivationRow> rows = jdbc.query(
                "SELECT " + ACTIVATION_COLUMNS + " FROM site_activation_request WHERE id = ? FOR UPDATE",
                ACTIVATION_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询中心最新版本申请；调用方须持有中心行锁以保证版本号分配串行。 */
    public ActivationRow findLatestBySite(String siteId) {
        List<ActivationRow> rows = jdbc.query(
                "SELECT " + ACTIVATION_COLUMNS + " FROM site_activation_request "
                        + "WHERE site_id = ? ORDER BY version DESC LIMIT 1",
                ACTIVATION_MAPPER, siteId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<QuotaRow> listQuotas(String requestId) {
        return jdbc.query(
                "SELECT request_id, site_id, version, block_no, quota FROM site_block_quota "
                        + "WHERE request_id = ? ORDER BY block_no",
                QUOTA_MAPPER, requestId);
    }

    /** 负责人确认；仅未确认时生效，返回受影响行数（0 表示已确认过）。 */
    public int markLeadConfirmed(String requestId, long confirmedAt) {
        return jdbc.update("UPDATE site_activation_request SET lead_confirmed = TRUE, "
                        + "lead_confirmed_at = ? WHERE id = ? AND lead_confirmed = FALSE",
                confirmedAt, requestId);
    }

    /** 独立复核人确认；仅未确认时生效，返回受影响行数（0 表示已确认过）。 */
    public int markReviewerConfirmed(String requestId, String reviewerActorId, long confirmedAt) {
        return jdbc.update("UPDATE site_activation_request SET reviewer_confirmed = TRUE, "
                        + "reviewer_actor_id = ?, reviewer_confirmed_at = ? "
                        + "WHERE id = ? AND reviewer_confirmed = FALSE",
                reviewerActorId, confirmedAt, requestId);
    }

    /**
     * 双人齐备后置为 ACTIVATED；仅 PENDING 且两个确认均为 TRUE 时生效。
     *
     * @return 受影响行数；0 表示状态不满足
     */
    public int markActivated(String requestId, long activatedAt) {
        return jdbc.update("UPDATE site_activation_request SET status = 'ACTIVATED', activated_at = ? "
                        + "WHERE id = ? AND status = 'PENDING' "
                        + "AND lead_confirmed = TRUE AND reviewer_confirmed = TRUE",
                activatedAt, requestId);
    }

    public void insertSnapshot(SnapshotRow row) {
        jdbc.update("INSERT INTO site_block_snapshot (site_id, version, block_no, frozen_quota, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                row.siteId(), row.version(), row.blockNo(), row.frozenQuota(), row.createdAt());
    }

    public List<SnapshotRow> listSnapshots(String siteId, int version) {
        return jdbc.query(
                "SELECT site_id, version, block_no, frozen_quota, created_at FROM site_block_snapshot "
                        + "WHERE site_id = ? AND version = ? ORDER BY block_no",
                SNAPSHOT_MAPPER, siteId, version);
    }
}
