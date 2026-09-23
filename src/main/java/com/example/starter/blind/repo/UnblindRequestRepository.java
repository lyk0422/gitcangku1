package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 揭盲申请数据访问；同一分配至多一个有效待审申请由唯一列 pending_allocation_id 保证。
 * 到期普通查询不写库，状态由服务层按时钟计算；仅在同事务重申时归档过期占位。
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
            Long reviewedAt,
            Integer validMinutes,
            Long expiresAt,
            String rejectReason,
            Long terminatedAt) {
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
            (Long) rs.getObject("reviewed_at"),
            (Integer) rs.getObject("valid_minutes"),
            (Long) rs.getObject("expires_at"),
            rs.getString("reject_reason"),
            (Long) rs.getObject("terminated_at"));

    private static final String COLUMNS =
            "id, experiment_id, participant_id, allocation_id, reason, applicant_actor, "
                    + "reviewer_actor, status, treatment, created_at, reviewed_at, "
                    + "valid_minutes, expires_at, reject_reason, terminated_at";

    private final JdbcTemplate jdbc;

    public UnblindRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入待审申请；若该分配已有有效待审申请，唯一索引 uq_unblind_pending 触发异常。
     */
    public void insertPending(UnblindRequestRow row) {
        jdbc.update("INSERT INTO unblind_request ("
                        + "id, experiment_id, participant_id, allocation_id, reason, applicant_actor, "
                        + "reviewer_actor, status, treatment, created_at, reviewed_at, "
                        + "pending_allocation_id, valid_minutes, expires_at, reject_reason, terminated_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, NULL, 'PENDING', NULL, ?, NULL, ?, ?, ?, NULL, NULL)",
                row.id(), row.experimentId(), row.participantId(), row.allocationId(),
                row.reason(), row.applicantActor(), row.createdAt(),
                row.allocationId(), row.validMinutes(), row.expiresAt());
    }

    public UnblindRequestRow findById(String requestId) {
        List<UnblindRequestRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM unblind_request WHERE id = ?", MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 行级锁定申请，保证批准/拒绝/撤销并发安全。
     */
    public UnblindRequestRow lockById(String requestId) {
        List<UnblindRequestRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM unblind_request WHERE id = ? FOR UPDATE",
                MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 行级锁定某分配当前的有效待审申请；重申事务内据此串行化并发新申请。
     */
    public UnblindRequestRow lockPendingByAllocation(long allocationId) {
        List<UnblindRequestRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM unblind_request "
                        + "WHERE pending_allocation_id = ? AND status = 'PENDING' FOR UPDATE",
                MAPPER, allocationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 批准：仅 PENDING 可批准，写入处理代码、批准人与时间，并释放待审唯一占位。
     *
     * @return 受影响行数；0 表示不存在或已终态
     */
    public int approve(String requestId, String reviewerActor, String treatment, long reviewedAt) {
        return jdbc.update("UPDATE unblind_request SET status = 'APPROVED', reviewer_actor = ?, "
                        + "treatment = ?, reviewed_at = ?, pending_allocation_id = NULL, "
                        + "reject_reason = NULL, terminated_at = ? "
                        + "WHERE id = ? AND status = 'PENDING'",
                reviewerActor, treatment, reviewedAt, reviewedAt, requestId);
    }

    /**
     * 拒绝：仅 PENDING 可拒绝，写入非空拒绝原因、拒绝人与时间，并释放待审唯一占位；
     * 不写入任何处理代码。
     *
     * @return 受影响行数；0 表示不存在或已终态
     */
    public int reject(String requestId, String reviewerActor, String rejectReason, long rejectedAt) {
        return jdbc.update("UPDATE unblind_request SET status = 'REJECTED', reviewer_actor = ?, "
                        + "reject_reason = ?, reviewed_at = NULL, treatment = NULL, "
                        + "pending_allocation_id = NULL, terminated_at = ? "
                        + "WHERE id = ? AND status = 'PENDING'",
                reviewerActor, rejectReason, rejectedAt, requestId);
    }

    /**
     * 撤销：仅申请人本人、仅 PENDING 可撤销，释放待审唯一占位；不写入处理代码。
     *
     * @return 受影响行数；0 表示不存在或非本人有效待审
     */
    public int cancel(String requestId, String applicantActor, long cancelledAt) {
        return jdbc.update("UPDATE unblind_request SET status = 'CANCELLED', "
                        + "reviewed_at = NULL, treatment = NULL, reject_reason = NULL, "
                        + "pending_allocation_id = NULL, terminated_at = ? "
                        + "WHERE id = ? AND status = 'PENDING' AND applicant_actor = ?",
                cancelledAt, requestId, applicantActor);
    }

    /**
     * 归档过期占位：仅 PENDING 可置 EXPIRED，终态时间固定为到期时刻，
     * reviewer_actor 保持 NULL（不伪造人工处理人），并释放待审唯一占位。
     * 历史无 expires_at 列值的 PENDING 行按创建时间+30分钟兜底。
     *
     * @return 受影响行数；0 表示已被并发终态或不再是 PENDING
     */
    public int markExpired(String requestId) {
        return jdbc.update("UPDATE unblind_request SET status = 'EXPIRED', "
                        + "pending_allocation_id = NULL, "
                        + "terminated_at = COALESCE(expires_at, created_at + 1800000) "
                        + "WHERE id = ? AND status = 'PENDING'",
                requestId);
    }
}
