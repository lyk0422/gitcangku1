package com.example.starter.exposure.domain;

import java.util.List;

/**
 * 公告 PO。公告以 campaignId 唯一，创建时固定每 UTC 日总额度与每访客每日上限。
 *
 * @param campaignId          公告编号，全局唯一
 * @param dailyTotalCap       每 UTC 日总额度，单位次，取值 1～100000
 * @param perVisitorDailyCap  每访客每 UTC 日上限，单位次，取值 1～100000
 * @param createdAtUtc        创建时刻（epoch 毫秒，UTC）
 * @param creativeIds         固定有序轮换创意编号（2～5 个，下标即轮换顺序）；
 *                            未配置轮换的公告为空列表，创建后不可变
 */
public record Campaign(
        String campaignId,
        int dailyTotalCap,
        int perVisitorDailyCap,
        long createdAtUtc,
        List<String> creativeIds
) {
}
