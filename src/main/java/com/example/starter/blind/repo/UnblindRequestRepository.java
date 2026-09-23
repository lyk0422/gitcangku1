package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 揭盲申请数据访问；同一分配至多一个待审申请由唯一列 pending_allocation_id 保证。
 * 终态（APPROVED/CANCELLED/REJECTED/EXPIRED）互转由条件 UPDATE 的 status='PENDING' 守卫；
 * expires_at 为 NULL 的历史待审申请按 created_at + 30 分钟计算有效期。
 */
@Repository
public class UnblindRequestRepository {

    /** 默认有效时长（分钟），同时用于历史 PENDING 申请的有效期回填。 */
    public static final int DEFAULT_VALID_MINUTES = 30;

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
            int validMinutes,
            Long expiresAt,
            String rejectReason,
            String terminatedActor,
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
            rs.getInt("valid_minutes"),
            (Long) rs.getObject("expires_at"),
            rs.getString("reject_reason"),
            rs.getString("terminated_actor"),
            (Long) rs.getObject("terminated_at"));

    private static final String COLUMNS =
            "id, experiment_id, participant_id, allocation_id, reason, applicant_actor, "
                    + "reviewer_actor, status, treatment, created_at, reviewed_at, "
                    + "valid_minutes, expires_at, reject_reason, terminated_actor, terminated_at";

    /** 历史 PENDING（expires_at 为 NULL）的有效期：创建时间 + 30 分钟。 */
    private static final String EFFECTIVE_EXPIRES =
            "COALESCE(expires_at, created_at + " + (DEFAULT_VALID_MINUTES * 60_000L) + ")";

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
                        + "reviewer_actor, status, treatment, created_at, reviewed_at, "
                        + "pending_allocation_id, valid_minutes, expires_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, NULL, 'PENDING', NULL, ?, NULL, ?, ?, ?)",
                row.id(), row.experimentId(), row.participantId(), row.allocationId(),
                row.reason(), row.applicantActor(), row.createdAt(), row.allocationId(),
                row.validMinutes(), row.expiresAt());
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

    public UnblindRequestRow findPendingByAllocation(long allocationId) {
        List<UnblindRequestRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM unblind_request "
                        + "WHERE pending_allocation_id = ? AND status = 'PENDING'",
                MAPPER, allocationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 把同一分配已到期的 PENDING 占位归档为 EXPIRED：释放唯一待审占位，
     * 终态时间固定为 expiresAt，不写人工处理人。须在新申请事务内、插入前调用。
     *
     * @return 被归档的行数
     */
    public int archiveExpiredPending(long allocationId, long nowMillis) {
        return jdbc.update("UPDATE unblind_request SET status = 'EXPIRED', "
                        + "pending_allocation_id = NULL, terminated_actor = NULL, "
                        + "terminated_at = " + EFFECTIVE_EXPIRES + " "
                        + "WHERE pending_allocation_id = ? AND status = 'PENDING' "
                        + "AND " + EFFECTIVE_EXPIRES + " <= ?",
                allocationId, nowMillis);
    }

    /**
     * 批准：仅未过期的 PENDING 可批准，写入处理代码、批准人与时间，并释放待审唯一占位。
     *
     * @return 受影响行数；0 表示不存在、已终态或已过期
     */
    public int approve(String requestId, String reviewerActor, String treatment,
                       long reviewedAt, long nowMillis) {
        return jdbc.update("UPDATE unblind_request SET status = 'APPROVED', reviewer_actor = ?, "
                        + "treatment = ?, reviewed_at = ?, pending_allocation_id = NULL "
                        + "WHERE id = ? AND status = 'PENDING' AND " + EFFECTIVE_EXPIRES + " > ?",
                reviewerActor, treatment, reviewedAt, requestId, nowMillis);
    }

    /**
     * 拒绝：仅未过期的 PENDING 可拒绝，写入非空拒绝原因、处理人与时间，释放待审占位。
     *
     * @return 受影响行数；0 表示不存在、已终态或已过期
     */
    public int reject(String requestId, String reviewerActor, String rejectReason,
                      long terminatedAt, long nowMillis) {
        return jdbc.update("UPDATE unblind_request SET status = 'REJECTED', "
                        + "reject_reason = ?, terminated_actor = ?, terminated_at = ?, "
                        + "pending_allocation_id = NULL "
                        + "WHERE id = ? AND status = 'PENDING' AND " + EFFECTIVE_EXPIRES + " > ?",
                rejectReason, reviewerActor, terminatedAt, requestId, nowMillis);
    }

    /**
     * 撤销：仅申请人本人、未过期的 PENDING 可撤销，写入处理人与时间，释放待审占位。
     *
     * @return 受影响行数；0 表示不存在、已终态或已过期
     */
    public int cancel(String requestId, String applicantActor, long terminatedAt, long nowMillis) {
        return jdbc.update("UPDATE unblind_request SET status = 'CANCELLED', "
                        + "terminated_actor = ?, terminated_at = ?, pending_allocation_id = NULL "
                        + "WHERE id = ? AND status = 'PENDING' AND applicant_actor = ? "
                        + "AND " + EFFECTIVE_EXPIRES + " > ?",
                applicantActor, terminatedAt, requestId, applicantActor, nowMillis);
    }
}
