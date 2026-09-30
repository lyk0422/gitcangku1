package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.SuppressionCancelledReservation;

import java.time.LocalDate;

/**
 * 抑制加入时被取消预占的审计明细视图。
 *
 * @param suppressionId  所属抑制周期编号
 * @param reservationId  被取消的预占单编号
 * @param campaignId     预占所属公告编号
 * @param utcDate        预占额度所属 UTC 日，格式 yyyy-MM-dd
 * @param releasedQuota  该笔释放的额度，单位次（当前恒为 1）
 * @param cancelledAtUtc 取消时刻，epoch 毫秒，UTC
 */
public record CancelledReservationView(
        String suppressionId,
        String reservationId,
        String campaignId,
        LocalDate utcDate,
        int releasedQuota,
        long cancelledAtUtc
) {
    public static CancelledReservationView from(SuppressionCancelledReservation item) {
        return new CancelledReservationView(
                item.suppressionId(),
                item.reservationId(),
                item.campaignId(),
                item.utcDate(),
                item.releasedQuota(),
                item.cancelledAtUtc());
    }
}
