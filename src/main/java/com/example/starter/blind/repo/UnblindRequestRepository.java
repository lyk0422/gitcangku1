package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 揭盲申请数据访问；同一分配至多一个“有效待审”申请由唯一列 pending_allocation_id 保证。
 * 到期是查询时钟语义：普通查询不写库；仅在重新申请的同一事务内归档旧过期占位。
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
            Long expiresAt,
            String handlerActor,
            Long terminatedAt,
            String terminalReason) {
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
            (Long) rs.getObject("expires_at"),
            rs.getString("handler_actor"),
            (Long) rs.getObject("terminated_at"),
            rs.getString("terminal_reason"));

    private static final String COLUMNS =
            "id, experiment_id, participant_id, allocation_id, reason, applicant_actor, "
                    + "reviewer_actor, status, treatment, created_at, reviewed_at, expires_at, "
                    + "handler_actor, terminated_at, terminal_reason";

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
                        + "pending_allocation_id, expires_at, handler_actor, terminated_at, terminal_reason"
                        + ") VALUES (?, ?, ?, ?, ?, ?, NULL, 'PENDING', NULL, ?, NULL, ?, ?, NULL, NULL, NULL)",
                row.id(), row.experimentId(), row.participantId(), row.allocationId(),
                row.reason(), row.applicantActor(), row.createdAt(), row.allocationId(),
                row.expiresAt());
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
     * 行级锁定某分配当前仍标记为 PENDING 的申请（含已到期但尚未归档的占位）；
     * 仅重新申请事务使用，据此归档旧过期占位并串行化并发新申请。
     */
    public UnblindRequestRow lockPendingByAllocation(long allocationId) {
        List<UnblindRequestRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM unblind_request "
                        + "WHERE pending_allocation_id = ? AND status = 'PENDING' FOR UPDATE",
                MAPPER, allocationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 批准：仅 PENDING 可批准，写入处理代码、批准人与时间，并释放待审唯一占位；
     * 同时回填历史空行的到期时刻（批准结果不追溯设限，仅用于视图完整）。
     *
     * @return 受影响行数；0 表示不存在或已非待审
     */
    public int approve(String requestId, String reviewerActor, String treatment,
                       long reviewedAt, long fallbackExpiresAt) {
        return jdbc.update("UPDATE unblind_request SET status = 'APPROVED', reviewer_actor = ?, "
                        + "treatment = ?, reviewed_at = ?, pending_allocation_id = NULL, "
                        + "handler_actor = ?, terminated_at = ?, terminal_reason = NULL, "
                        + "expires_at = COALESCE(expires_at, ?) "
                        + "WHERE id = ? AND status = 'PENDING'",
                reviewerActor, treatment, reviewedAt, reviewerActor, reviewedAt,
                fallbackExpiresAt, requestId);
    }

    /**
     * 拒绝：仅 PENDING 可拒绝，写入拒绝原因、处理人与时间，释放待审占位，不写入盲底。
     *
     * @return 受影响行数；0 表示不存在或已非待审
     */
    public int reject(String requestId, String reviewerActor, String rejectReason,
                      long rejectedAt, long fallbackExpiresAt) {
        return jdbc.update("UPDATE unblind_request SET status = 'REJECTED', "
                        + "pending_allocation_id = NULL, handler_actor = ?, terminated_at = ?, "
                        + "terminal_reason = ?, expires_at = COALESCE(expires_at, ?) "
                        + "WHERE id = ? AND status = 'PENDING'",
                reviewerActor, rejectedAt, rejectReason, fallbackExpiresAt, requestId);
    }

    /**
     * 撤销：仅申请人本人对 PENDING 可撤销，处理人记为申请人，释放待审占位。
     *
     * @return 受影响行数；0 表示不存在或已非待审
     */
    public int cancel(String requestId, String applicantActor, long cancelledAt,
                      long fallbackExpiresAt) {
        return jdbc.update("UPDATE unblind_request SET status = 'CANCELLED', "
                        + "pending_allocation_id = NULL, handler_actor = ?, terminated_at = ?, "
                        + "terminal_reason = '申请人主动撤销', "
                        + "expires_at = COALESCE(expires_at, ?) "
                        + "WHERE id = ? AND status = 'PENDING'",
                applicantActor, cancelledAt, fallbackExpiresAt, requestId);
    }

    /**
     * 到期归档：把已过有效期的 PENDING 占位置为 EXPIRED，处理人固定为 NULL，
     * 终止时间固定为到期时刻，并回填到期时刻列；仅在重新申请事务内调用。
     *
     * @return 受影响行数；0 表示已被并发裁决
     */
    public int archiveExpired(String requestId, long effectiveExpiresAt) {
        return jdbc.update("UPDATE unblind_request SET status = 'EXPIRED', "
                        + "pending_allocation_id = NULL, handler_actor = NULL, "
                        + "terminated_at = ?, terminal_reason = '申请已过期', expires_at = ? "
                        + "WHERE id = ? AND status = 'PENDING'",
                effectiveExpiresAt, effectiveExpiresAt, requestId);
    }
}
