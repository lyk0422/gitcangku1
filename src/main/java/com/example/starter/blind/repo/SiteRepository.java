package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 试验中心与不可变双人激活记录数据访问。
 * 中心状态流转全部使用条件 UPDATE 并由行级锁串行化；激活记录只插入、永不更新或删除。
 */
@Repository
public class SiteRepository {

    /** 中心行。status 取值 INACTIVE/ACTIVE/SUSPENDED/CLOSED；generation 未激活为 0。 */
    public record SiteRow(
            long id,
            String experimentId,
            String siteCode,
            String status,
            int targetEnrollmentLimit,
            int generation,
            long createdAt,
            long updatedAt) {
    }

    /** 不可变双人激活记录行；activationKey 仅供服务层比对，不进入普通视图。 */
    public record ActivationRecordRow(
            long id,
            String experimentId,
            String siteCode,
            int generation,
            String activationKey,
            String firstConfirmerActor,
            String secondConfirmerActor,
            int targetEnrollmentLimit,
            long activatedAt) {
    }

    /** 双人激活首确认暂存行；generation 为首确认时中心代次。 */
    public record PendingRow(
            String experimentId,
            String siteCode,
            int generation,
            String activationKey,
            String firstConfirmerActor,
            long createdAt) {
    }

    private static final String SITE_COLUMNS =
            "id, experiment_id, site_code, status, target_enrollment_limit, generation, "
                    + "created_at, updated_at";

    private static final RowMapper<SiteRow> SITE_MAPPER = (rs, n) -> new SiteRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("site_code"),
            rs.getString("status"),
            rs.getInt("target_enrollment_limit"),
            rs.getInt("generation"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private static final String RECORD_COLUMNS =
            "id, experiment_id, site_code, generation, activation_key, first_confirmer_actor, "
                    + "second_confirmer_actor, target_enrollment_limit, activated_at";

    private static final RowMapper<ActivationRecordRow> RECORD_MAPPER = (rs, n) ->
            new ActivationRecordRow(
                    rs.getLong("id"),
                    rs.getString("experiment_id"),
                    rs.getString("site_code"),
                    rs.getInt("generation"),
                    rs.getString("activation_key"),
                    rs.getString("first_confirmer_actor"),
                    rs.getString("second_confirmer_actor"),
                    rs.getInt("target_enrollment_limit"),
                    rs.getLong("activated_at"));

    private final JdbcTemplate jdbc;

    public SiteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertSite(SiteRow row) {
        jdbc.update("INSERT INTO site (experiment_id, site_code, status, "
                        + "target_enrollment_limit, generation, created_at, updated_at) "
                        + "VALUES (?, ?, 'INACTIVE', ?, 0, ?, ?)",
                row.experimentId(), row.siteCode(), row.targetEnrollmentLimit(),
                row.createdAt(), row.updatedAt());
    }

    public SiteRow find(String experimentId, String siteCode) {
        List<SiteRow> rows = jdbc.query(
                "SELECT " + SITE_COLUMNS + " FROM site "
                        + "WHERE experiment_id = ? AND site_code = ?",
                SITE_MAPPER, experimentId, siteCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定中心，串行化同中心的激活/暂停/恢复/关闭/分配并发。 */
    public SiteRow lock(String experimentId, String siteCode) {
        List<SiteRow> rows = jdbc.query(
                "SELECT " + SITE_COLUMNS + " FROM site "
                        + "WHERE experiment_id = ? AND site_code = ? FOR UPDATE",
                SITE_MAPPER, experimentId, siteCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 激活/恢复：仅 INACTIVE 或 SUSPENDED 可激活为 ACTIVE，代次加 1。
     *
     * @return 受影响行数；0 表示状态已被并发改变
     */
    public int activate(String experimentId, String siteCode, int generation, long updatedAt) {
        return jdbc.update("UPDATE site SET status = 'ACTIVE', generation = ?, updated_at = ? "
                        + "WHERE experiment_id = ? AND site_code = ? "
                        + "AND status IN ('INACTIVE', 'SUSPENDED') AND generation = ?",
                generation, updatedAt, experimentId, siteCode, generation - 1);
    }

    /**
     * 暂停：仅 ACTIVE 可暂停，代次不变。
     *
     * @return 受影响行数；0 表示非 ACTIVE
     */
    public int suspend(String experimentId, String siteCode, long updatedAt) {
        return jdbc.update("UPDATE site SET status = 'SUSPENDED', updated_at = ? "
                        + "WHERE experiment_id = ? AND site_code = ? AND status = 'ACTIVE'",
                updatedAt, experimentId, siteCode);
    }

    /**
     * 关闭：非 CLOSED 均可关闭，关闭后不可恢复。
     *
     * @return 受影响行数；0 表示已关闭
     */
    public int close(String experimentId, String siteCode, long updatedAt) {
        return jdbc.update("UPDATE site SET status = 'CLOSED', updated_at = ? "
                        + "WHERE experiment_id = ? AND site_code = ? AND status <> 'CLOSED'",
                updatedAt, experimentId, siteCode);
    }

    public long countAssignments(String experimentId, String siteCode) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = ? AND site_code = ?",
                Long.class, experimentId, siteCode);
        return count == null ? 0 : count;
    }

    public void insertActivationRecord(ActivationRecordRow row) {
        jdbc.update("INSERT INTO site_activation_record (experiment_id, site_code, generation, "
                        + "activation_key, first_confirmer_actor, second_confirmer_actor, "
                        + "target_enrollment_limit, activated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.siteCode(), row.generation(), row.activationKey(),
                row.firstConfirmerActor(), row.secondConfirmerActor(),
                row.targetEnrollmentLimit(), row.activatedAt());
    }

    public List<ActivationRecordRow> findActivationRecords(String experimentId, String siteCode) {
        return jdbc.query(
                "SELECT " + RECORD_COLUMNS + " FROM site_activation_record "
                        + "WHERE experiment_id = ? AND site_code = ? ORDER BY generation",
                RECORD_MAPPER, experimentId, siteCode);
    }

    private static final RowMapper<PendingRow> PENDING_MAPPER = (rs, n) -> new PendingRow(
            rs.getString("experiment_id"),
            rs.getString("site_code"),
            rs.getInt("generation"),
            rs.getString("activation_key"),
            rs.getString("first_confirmer_actor"),
            rs.getLong("created_at"));

    public void insertPending(PendingRow row) {
        jdbc.update("INSERT INTO site_activation_pending (experiment_id, site_code, generation, "
                        + "activation_key, first_confirmer_actor, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.siteCode(), row.generation(), row.activationKey(),
                row.firstConfirmerActor(), row.createdAt());
    }

    public PendingRow findPending(String experimentId, String siteCode) {
        List<PendingRow> rows = jdbc.query(
                "SELECT experiment_id, site_code, generation, activation_key, "
                        + "first_confirmer_actor, created_at FROM site_activation_pending "
                        + "WHERE experiment_id = ? AND site_code = ?",
                PENDING_MAPPER, experimentId, siteCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void deletePending(String experimentId, String siteCode) {
        jdbc.update("DELETE FROM site_activation_pending "
                + "WHERE experiment_id = ? AND site_code = ?", experimentId, siteCode);
    }

    /** 该中心名下分配是否存在待审揭盲申请；关闭中心前必须为 0。 */
    public long countPendingUnblindRequests(String experimentId, String siteCode) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request u "
                        + "JOIN allocation a ON a.id = u.allocation_id "
                        + "WHERE u.experiment_id = ? AND a.site_code = ? AND u.status = 'PENDING'",
                Long.class, experimentId, siteCode);
        return count == null ? 0 : count;
    }
}
