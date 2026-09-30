package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.SuppressionStatus;

import java.util.List;

/**
 * 访客抑制名单视图：当前状态/起止、最近一次变更取消的预占与额度变化、
 * 全部抑制周期（含每笔取消明细）以及历史已确认曝光（仅审计）。
 *
 * @param visitorId              合成访客编号
 * @param status                 当前抑制状态：ACTIVE/RELEASED
 * @param suppressionId          当前（或最近一个）抑制周期编号
 * @param effectiveFromUtc       本次抑制生效时刻，epoch 毫秒，UTC
 * @param effectiveToUtc         本次抑制解除时刻；ACTIVE 期间为 null
 * @param cancelledCount         本次生效周期加入时取消的未确认预占笔数，单位笔
 * @param releasedQuota          本次生效周期取消预占释放的额度合计（总额度与访客额度各计该值），单位次
 * @param periods                全部抑制周期（按生效时刻倒序）
 * @param confirmedReservations  历史已确认曝光（保留审计，不再用于发放新预占）
 * @param queriedAtUtc           查询时刻，epoch 毫秒，UTC
 */
public record SuppressionResponse(
        String visitorId,
        SuppressionStatus status,
        String suppressionId,
        long effectiveFromUtc,
        Long effectiveToUtc,
        int cancelledCount,
        int releasedQuota,
        List<SuppressionPeriodView> periods,
        List<ConfirmedReservationView> confirmedReservations,
        long queriedAtUtc
) {
}
