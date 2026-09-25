package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.SuppressionInterval;
import com.example.starter.exposure.domain.SuppressionIntervalStatus;

/**
 * 抑制区间视图。
 *
 * @param intervalId             区间编号
 * @param campaignId             所属公告编号
 * @param visitorId              访客编号
 * @param validFromUtc           生效起始时刻（含），epoch 毫秒，UTC
 * @param validUntilUtc          生效结束时刻（不含），epoch 毫秒，UTC；提前结束后为缩短值
 * @param originalValidUntilUtc  首次提前结束前的原始结束时刻；未提前结束为 null
 * @param status                 区间状态（ACTIVE/EARLY_ENDED/DELETED）
 * @param createdAtUtc           创建时刻，epoch 毫秒，UTC
 * @param deletedAtUtc           删除时刻，epoch 毫秒，UTC；未删除为 null
 */
public record SuppressionIntervalResponse(
        String intervalId,
        String campaignId,
        String visitorId,
        long validFromUtc,
        long validUntilUtc,
        Long originalValidUntilUtc,
        SuppressionIntervalStatus status,
        long createdAtUtc,
        Long deletedAtUtc
) {
    public static SuppressionIntervalResponse from(SuppressionInterval interval) {
        return new SuppressionIntervalResponse(
                interval.intervalId(),
                interval.campaignId(),
                interval.visitorId(),
                interval.validFromUtc(),
                interval.validUntilUtc(),
                interval.originalValidUntilUtc(),
                interval.status(),
                interval.createdAtUtc(),
                interval.deletedAtUtc());
    }
}
