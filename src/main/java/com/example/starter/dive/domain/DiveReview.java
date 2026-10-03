package com.example.starter.dive.domain;

import java.time.Instant;
import java.util.List;

/**
 * 审核/复核记录，按点位版本冻结结果，追加点位后原记录过期并形成复核链。
 *
 * @param id           审核记录ID
 * @param sessionId    所属作业ID
 * @param pointVersion 审核时点位的版本号
 * @param kind         记录类型：INITIAL=首次审核，RE_REVIEW=复核
 * @param decision     审核结论：PASSED=通过，REJECTED=拒绝
 * @param status       记录状态：ACTIVE=当前有效，EXPIRED=已过期
 * @param requestId    审核请求的幂等ID，系统复核为空
 * @param supersedesId 复核链中本记录取代的上一条审核记录ID，首条为空
 * @param anomalies    异常明细，通过时为空列表
 * @param createdAtUtc 审核时刻（UTC）
 */
public record DiveReview(
        long id,
        long sessionId,
        int pointVersion,
        ReviewKind kind,
        ReviewDecision decision,
        ReviewStatus status,
        String requestId,
        Long supersedesId,
        List<Anomaly> anomalies,
        Instant createdAtUtc) {

    /** 记录类型。 */
    public enum ReviewKind {
        /** 客户端请求的首次审核。 */
        INITIAL,
        /** 追加勘误点后系统生成的复核。 */
        RE_REVIEW
    }

    /** 审核结论。 */
    public enum ReviewDecision {
        /** 通过，点位版本被冻结。 */
        PASSED,
        /** 拒绝，附带具体异常。 */
        REJECTED
    }

    /** 记录状态。 */
    public enum ReviewStatus {
        /** 当前有效。 */
        ACTIVE,
        /** 因追加点位已过期。 */
        EXPIRED
    }
}