package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 中心启用申请、双人确认历史与冻结名额快照的数据访问。
 */
@Repository
public class ActivationRepository {

    /** 启用申请行。 */
    public record ActivationRow(
            String activationId,
            String experimentId,
            String siteId,
            int configVersion,
            String leadActor,
            String reviewerActor,
            String status,
            long createdAt,
            Long confirmedAt) {
    }

    /** 申请冻结的区组名额快照行。 */
    public record ActivationBlockRow(String activationId, String blockCode, int quota) {
    }

    /** 双人确认历史行。confirmRole 取值 LEAD/REVIEWER。 */
    public record ConfirmationRow(
            long id,
            String activationId,
            String actorId,
            String confirmRole,
            int configVersion,
            long confirmedAt) {
    }

    private static final RowMapper<ActivationRow> ACTIVATION_MAPPER = (rs, n) ->
            new ActivationRow(
                    rs.getString("activation_id"),
                    rs.getString("experiment_id"),
                    rs.getString("site_id"),
                    rs.getInt("config_version"),
                    rs.getString("lead_actor"),
                    rs.getString("reviewer_actor"),
                    rs.getString("status"),
                    rs.getLong("created_at"),
                    (Long) rs.getObject("confirmed_at"));

    private static final RowMapper<ActivationBlockRow> ACTIVATION_BLOCK_MAPPER = (rs, n) ->
            new ActivationBlockRow(rs.getString("activation_id"),
                    rs.getString("block_code"), rs.getInt("quota"));

    private static final RowMapper<ConfirmationRow> CONFIRMATION_MAPPER = (rs, n) ->
            new ConfirmationRow(
                    rs.getLong("id"),
                    rs.getString("activation_id"),
                    rs.getString("actor_id"),
                    rs.getString("confirm_role"),
                    rs.getInt("config_version"),
                    rs.getLong("confirmed_at"));

    private final JdbcTemplate jdbc;

    public ActivationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入待复核启用申请；同一中心重复申请由 uq_site_activation_site 唯一约束拒绝。
     */
    public void insertPending(ActivationRow row) {
        jdbc.update("INSERT INTO site_activation ("
                        + "activation_id, experiment_id, site_id, config_version, lead_actor, "
                        + "reviewer_actor, status, created_at, confirmed_at"
                        + ") VALUES (?, ?, ?, ?, ?, NULL, 'PENDING_CONFIRMATION', ?, NULL)",
                row.activationId(), row.experimentId(), row.siteId(), row.configVersion(),
                row.leadActor(), row.createdAt());
    }

    public boolean isDuplicateSite(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_site_activation_site");
    }

    public ActivationRow findById(String activationId) {
        List<ActivationRow> rows = jdbc.query(
                "SELECT activation_id, experiment_id, site_id, config_version, lead_actor, "
                        + "reviewer_actor, status, created_at, confirmed_at "
                        + "FROM site_activation WHERE activation_id = ?",
                ACTIVATION_MAPPER, activationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定启用申请，串行化复核并发。 */
    public ActivationRow lockById(String activationId) {
        List<ActivationRow> rows = jdbc.query(
                "SELECT activation_id, experiment_id, site_id, config_version, lead_actor, "
                        + "reviewer_actor, status, created_at, confirmed_at "
                        + "FROM site_activation WHERE activation_id = ? FOR UPDATE",
                ACTIVATION_MAPPER, activationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ActivationRow findBySite(String experimentId, String siteId) {
        List<ActivationRow> rows = jdbc.query(
                "SELECT activation_id, experiment_id, site_id, config_version, lead_actor, "
                        + "reviewer_actor, status, created_at, confirmed_at "
                        + "FROM site_activation WHERE experiment_id = ? AND site_id = ?",
                ACTIVATION_MAPPER, experimentId, siteId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ActivationRow lockBySite(String experimentId, String siteId) {
        List<ActivationRow> rows = jdbc.query(
                "SELECT activation_id, experiment_id, site_id, config_version, lead_actor, "
                        + "reviewer_actor, status, created_at, confirmed_at "
                        + "FROM site_activation WHERE experiment_id = ? AND site_id = ? FOR UPDATE",
                ACTIVATION_MAPPER, experimentId, siteId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertActivationBlocks(List<ActivationBlockRow> rows) {
        jdbc.batchUpdate("INSERT INTO site_activation_block (activation_id, block_code, quota) "
                        + "VALUES (?, ?, ?)",
                rows, rows.size(), (ps, row) -> {
                    ps.setString(1, row.activationId());
                    ps.setString(2, row.blockCode());
                    ps.setInt(3, row.quota());
                });
    }

    public List<ActivationBlockRow> findActivationBlocks(String activationId) {
        return jdbc.query(
                "SELECT activation_id, block_code, quota FROM site_activation_block "
                        + "WHERE activation_id = ? ORDER BY block_code",
                ACTIVATION_BLOCK_MAPPER, activationId);
    }

    /**
     * 插入一条确认历史；同一申请同一角色重复确认由
     * uq_site_activation_confirmation_role 唯一约束拒绝。
     */
    public void insertConfirmation(ConfirmationRow row) {
        jdbc.update("INSERT INTO site_activation_confirmation ("
                        + "activation_id, actor_id, confirm_role, config_version, confirmed_at"
                        + ") VALUES (?, ?, ?, ?, ?)",
                row.activationId(), row.actorId(), row.confirmRole(),
                row.configVersion(), row.confirmedAt());
    }

    public boolean isDuplicateConfirmationRole(DuplicateKeyException e) {
        return e.getMessage() != null
                && e.getMessage().contains("uq_site_activation_confirmation_role");
    }

    public List<ConfirmationRow> findConfirmations(String activationId) {
        return jdbc.query(
                "SELECT id, activation_id, actor_id, confirm_role, config_version, confirmed_at "
                        + "FROM site_activation_confirmation WHERE activation_id = ? "
                        + "ORDER BY id",
                CONFIRMATION_MAPPER, activationId);
    }

    /**
     * 复核确认：PENDING_CONFIRMATION -> ACTIVE，写入复核人与时间；返回受影响行数。
     */
    public int markActive(String activationId, String reviewerActor, long confirmedAt) {
        return jdbc.update("UPDATE site_activation SET status = 'ACTIVE', reviewer_actor = ?, "
                        + "confirmed_at = ? WHERE activation_id = ? "
                        + "AND status = 'PENDING_CONFIRMATION'",
                reviewerActor, confirmedAt, activationId);
    }
}
