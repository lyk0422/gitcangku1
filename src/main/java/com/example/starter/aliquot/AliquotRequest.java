package com.example.starter.aliquot;

import java.time.LocalDateTime;

/**
 * 联合取样单实体，对应 aliquot_request 表。
 *
 * @param id               主键
 * @param aliquotKey       联合取样单业务键，全局唯一
 * @param applicantId      申请人
 * @param status           取样单状态
 * @param version          申请版本（首次确认后加 1）
 * @param firstReviewer    第一审核人，未确认时为 null
 * @param secondReviewer   第二审核人，未完成时为 null
 * @param firstConfirmedAt 第一次确认时间
 * @param secondConfirmedAt 第二次确认时间
 * @param rejectedAt       拒绝时间
 * @param rejectedBy       拒绝审核人
 * @param cancelledAt      取消时间
 * @param createdAt        申请时间
 * @param updatedAt        最近变更时间
 */
public record AliquotRequest(
        Long id,
        String aliquotKey,
        String applicantId,
        AliquotStatus status,
        long version,
        String firstReviewer,
        String secondReviewer,
        LocalDateTime firstConfirmedAt,
        LocalDateTime secondConfirmedAt,
        LocalDateTime rejectedAt,
        String rejectedBy,
        LocalDateTime cancelledAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
