package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.SuppressionPeriod;

import java.util.List;

/**
 * 单个抑制周期视图：含抑制起止与该周期取消预占、额度变化明细。
 *
 * @param suppressionId        抑制周期编号
 * @param effectiveFromUtc     生效时刻，epoch 毫秒，UTC
 * @param effectiveToUtc       解除时刻；未解除为 null
 * @param cancelledCount       加入时取消的未确认预占笔数，单位笔
 * @param releasedQuota        取消预占释放的额度合计（总额度与访客额度各计该值），单位次
 * @param joinRequestId        加入名单的幂等键
 * @param releaseRequestId     解除名单的幂等键；未解除为 null
 * @param cancelledReservations 被取消预占审计明细
 */
public record SuppressionPeriodView(
        String suppressionId,
        long effectiveFromUtc,
        Long effectiveToUtc,
        int cancelledCount,
        int releasedQuota,
        String joinRequestId,
        String releaseRequestId,
        List<CancelledReservationView> cancelledReservations
) {
    public static SuppressionPeriodView from(SuppressionPeriod period,
                                             List<CancelledReservationView> cancelledReservations) {
        return new SuppressionPeriodView(
                period.suppressionId(),
                period.effectiveFromUtc(),
                period.effectiveToUtc(),
                period.cancelledCount(),
                period.releasedQuota(),
                period.joinRequestId(),
                period.releaseRequestId(),
                cancelledReservations);
    }
}
