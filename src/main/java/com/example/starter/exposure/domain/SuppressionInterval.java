package com.example.starter.exposure.domain;

/**
 * 访客曝光抑制区间 PO。生效区间为 UTC 左闭右开 [validFromUtc, validUntilUtc)，
 * 同一公告同一访客的生效区间（ACTIVE 与 EARLY_ENDED）互不重叠。
 *
 * @param intervalId             区间编号，全局唯一
 * @param campaignId             所属公告编号
 * @param visitorId              合成访客编号
 * @param validFromUtc           生效起始时刻（含），epoch 毫秒，UTC
 * @param validUntilUtc          生效结束时刻（不含），epoch 毫秒，UTC；提前结束后为缩短值
 * @param originalValidUntilUtc  首次提前结束前的原始结束时刻，epoch 毫秒，UTC；未提前结束为 null
 * @param status                 区间状态
 * @param createdAtUtc           创建时刻，epoch 毫秒，UTC
 * @param deletedAtUtc           删除时刻，epoch 毫秒，UTC；未删除为 null
 */
public record SuppressionInterval(
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
    /**
     * 判断给定时刻是否命中本区间的生效范围（左闭右开）。
     * 仅 ACTIVE 状态参与命中判定，调用方负责过滤状态。
     *
     * @param nowUtc 判定时刻，epoch 毫秒，UTC
     * @return 是否命中
     */
    public boolean covers(long nowUtc) {
        return validFromUtc <= nowUtc && nowUtc < validUntilUtc;
    }

    /**
     * 判断本区间与 [fromUtc, untilUtc) 是否重叠（左闭右开区间重叠判定）。
     *
     * @param fromUtc  另一起始时刻（含）
     * @param untilUtc 另一结束时刻（不含）
     * @return 是否重叠
     */
    public boolean overlaps(long fromUtc, long untilUtc) {
        return validFromUtc < untilUtc && fromUtc < validUntilUtc;
    }
}
