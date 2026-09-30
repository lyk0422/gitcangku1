package com.example.starter.exposure.domain;

/**
 * 访客抑制名单当前状态 PO。每个访客至多一行（visitor_id 唯一）。
 *
 * @param visitorId        合成访客编号
 * @param status           当前抑制状态（ACTIVE/RELEASED）
 * @param effectiveFromUtc 本次抑制生效（加入名单）时刻，epoch 毫秒，UTC
 * @param effectiveToUtc   解除抑制时刻；ACTIVE 期间为 null
 * @param requestId        最近一次名单变更（加入/解除）的幂等键
 * @param cancelledCount   本次生效周期加入时取消的未确认预占笔数，单位笔；重复加入/解除不变更
 * @param releasedQuota    本次生效周期取消预占所释放的额度合计（总额度与访客额度各计该值），单位次
 * @param updatedAtUtc     最近一次名单变更时刻，epoch 毫秒，UTC
 */
public record Suppression(
        String visitorId,
        SuppressionStatus status,
        long effectiveFromUtc,
        Long effectiveToUtc,
        String requestId,
        int cancelledCount,
        int releasedQuota,
        long updatedAtUtc
) {
}
