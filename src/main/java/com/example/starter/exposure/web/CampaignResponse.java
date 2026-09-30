package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Campaign;

/**
 * 公告视图。
 *
 * @param campaignId          公告编号
 * @param dailyTotalCap       每 UTC 日总额度，单位次
 * @param perVisitorDailyCap  每访客每 UTC 日上限，单位次
 * @param createdAtUtc        创建时刻，epoch 毫秒，UTC
 * @param activityId          所属活动编号；未归入活动为 null
 */
public record CampaignResponse(
        String campaignId,
        int dailyTotalCap,
        int perVisitorDailyCap,
        long createdAtUtc,
        String activityId
) {
    public static CampaignResponse from(Campaign campaign) {
        return new CampaignResponse(
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.createdAtUtc(),
                campaign.activityId());
    }
}
