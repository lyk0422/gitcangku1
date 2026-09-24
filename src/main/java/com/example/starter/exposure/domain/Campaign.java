package com.example.starter.exposure.domain;

/**
 * 公告 PO。公告以 campaignId 唯一，创建时固定类别、每 UTC 日总额度与每访客每日上限。
 *
 * @param campaignId          公告编号，全局唯一
 * @param category            公告类别：CRITICAL / SERVICE / MARKETING
 * @param dailyTotalCap       每 UTC 日总额度，单位次，取值 1～100000
 * @param perVisitorDailyCap  每访客每 UTC 日上限，单位次，取值 1～100000
 * @param createdAtUtc        创建时刻（epoch 毫秒，UTC）
 */
public record Campaign(
        String campaignId,
        CampaignCategory category,
        int dailyTotalCap,
        int perVisitorDailyCap,
        long createdAtUtc
) {
}
