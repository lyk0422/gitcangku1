package com.example.starter.exposure.domain;

import java.time.LocalDate;

/**
 * 加入抑制名单时被取消的预占审计明细 PO。每笔被取消的 RESERVED 预占对应一行，
 * 记录其所属额度日与返还次数；已确认曝光不会出现在此表中。
 *
 * @param suppressionId 所属抑制周期编号
 * @param reservationId 被取消的预占单编号
 * @param campaignId    预占所属公告编号
 * @param utcDate       预占额度所属 UTC 日（即释放额度的账目日）
 * @param releasedQuota 该笔释放的额度，单位次（当前恒为 1）
 * @param cancelledAtUtc 取消时刻，epoch 毫秒，UTC
 */
public record SuppressionCancelledReservation(
        String suppressionId,
        String reservationId,
        String campaignId,
        LocalDate utcDate,
        int releasedQuota,
        long cancelledAtUtc
) {
}
