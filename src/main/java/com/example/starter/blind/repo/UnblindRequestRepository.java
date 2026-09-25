package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 揭盲申请数据访问；同一分配至多一个待审申请由唯一列 pending_allocation_id 保证。
 */
@Repository
public class UnblindRequestRepository {

    /** 揭盲申请行。treatment 为盲底，仅批准后允许返回给申请人。 */
    public record UnblindRequestRow(
            String id,
            String experimentId,
            String participantId,
            long allocationId,
            String reason,
            String applicantActor,
            String reviewerActor,
            String status,
            String treatment,
            long createdAt,
            Long reviewedAt) {
    }

    private static final RowMapper<UnblindRequestRow> MAPPER = (rs, n) -> new UnblindRequestRow(
            rs.getString("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getLong("allocation_id"),
            rs.getString("reason"),
            rs.getString("applicant_actor"),
            rs.getString("reviewer_actor"),
            rs.getString("status"),
            rs.getString("treatment"),
            rs.getLong("created_at"),
            (Long) rs.getObject("reviewed_at"));

    private static final String COLUMNS =
            "id, experiment_id, participant_id, allocation_id, reason, applicant_actor, "
                    + "reviewer_actor, status, treatment, created_at, reviewed_at";

    private final JdbcTemplate jdbc;

    public UnblindRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入待审申请；若该分配已有待审申请，唯一索引 uq_unblind_pending 触发异常。
     */
    public void insertPending(UnblindRequestRow row) {
        jdbc.update("INSERT INTO unblind_request ("
                        + "id, experiment_id, participant_id, allocation_id, reason, applicant_actor, "
                        + "reviewer_actor, status, treatment, created_at, reviewed_at, pending_allocation_id"
                        + ") VALUES (?, ?, ?, ?, ?, ?, NULL, 'PENDING', NULL, ?, NULL, ?)",
                row.id(), row.experimentId(), row.participantId(), row.allocationId(),
                row.reason(), row.applicantActor(), row.createdAt(), row.allocationId());
    }

    public UnblindRequestRow findById(String requestId) {
        List<UnblindRequestRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM unblind_request WHERE id = ?", MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 行级锁定申请，保证批准并发安全。
     */
    public UnblindRequestRow lockById(String requestId) {
        List<UnblindRequestRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM unblind_request WHERE id = ? FOR UPDATE",
                MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public UnblindRequestRow findPendingByAllocation(long allocationId) {
        List<UnblindRequestRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM unblind_request "
                        + "WHERE pending_allocation_id = ? AND status = 'PENDING'",
                MAPPER, allocationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 批准：仅 PENDING 可批准，写入处理代码、批准人与时间，并释放待审唯一占位。
     *
     * @return 受影响行数；0 表示不存在或已批准
     */
    public int approve(String requestId, String reviewerActor, String treatment, long reviewedAt) {
        return jdbc.update("UPDATE unblind_request SET status = 'APPROVED', reviewer_actor = ?, "
                        + "treatment = ?, reviewed_at = ?, pending_allocation_id = NULL "
                        + "WHERE id = ? AND status = 'PENDING'",
                reviewerActor, treatment, reviewedAt, requestId);
    }

    /**
     * 中心维度待审揭盲申请数：关闭中心前必须为 0，否则 422。
     */
    public long countPendingBySite(String experimentId, String siteCode) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request u "
                        + "JOIN allocation a ON u.allocation_id = a.id "
                        + "WHERE a.experiment_id = ? AND a.site_code = ? AND u.status = 'PENDING'",
                Long.class, experimentId, siteCode);
        return count == null ? 0 : count;
    }
}
