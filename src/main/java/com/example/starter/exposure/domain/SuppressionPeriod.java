package com.example.starter.exposure.domain;

/**
 * 访客抑制变更周期历史 PO。每次加入名单生成一行；解除时补写止期与解除幂等键。
 *
 * @param suppressionId     抑制周期编号（加入时生成），全局唯一
 * @param visitorId         合成访客编号
 * @param effectiveFromUtc  抑制生效时刻，epoch 毫秒，UTC
 * @param effectiveToUtc    抑制解除时刻；未解除为 null
 * @param cancelledCount    加入时取消的未确认预占笔数，单位笔
 * @param releasedQuota     取消预占释放的额度合计（总额度与访客额度各计该值），单位次
 * @param joinRequestId     加入名单的幂等键
 * @param releaseRequestId  解除名单的幂等键；未解除为 null
 * @param createdAtUtc      周期创建（加入）时刻，epoch 毫秒，UTC
 */
public record SuppressionPeriod(
        String suppressionId,
        String visitorId,
        long effectiveFromUtc,
        Long effectiveToUtc,
        int cancelledCount,
        int releasedQuota,
        String joinRequestId,
        String releaseRequestId,
        long createdAtUtc
) {
}
