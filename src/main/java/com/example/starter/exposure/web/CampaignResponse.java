package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Campaign;

/**
 * 公告视图。
 *
 * @param campaignId          公告编号
 * @param dailyTotalCap       每 UTC 日总额度，单位次
 * @param perVisitorDailyCap  每访客每 UTC 日上限，单位次
 * @param cooldownMinutes     同一访客两次 CONFIRMED 曝光之间的最短冷却分钟数，0 表示不限制
 * @param version             冷却配置乐观锁版本号，修改冷却配置须携带为 expectedVersion
 * @param createdAtUtc        创建时刻，epoch 毫秒，UTC
 */
public record CampaignResponse(
        String campaignId,
        int dailyTotalCap,
        int perVisitorDailyCap,
        int cooldownMinutes,
        long version,
        long createdAtUtc
) {
    public static CampaignResponse from(Campaign campaign) {
        return new CampaignResponse(
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.cooldownMinutes(),
                campaign.version(),
                campaign.createdAtUtc());
    }
}
