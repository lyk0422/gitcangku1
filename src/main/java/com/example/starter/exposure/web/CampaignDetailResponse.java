package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Campaign;

/**
 * 公告明细视图（含活动归属）。
 *
 * @param campaignId          公告编号
 * @param dailyTotalCap       每 UTC 日总额度，单位次
 * @param perVisitorDailyCap  每访客每 UTC 日上限，单位次
 * @param activityId          所属活动编号；未归入活动为 null
 * @param createdAtUtc        创建时刻，epoch 毫秒，UTC
 */
public record CampaignDetailResponse(
        String campaignId,
        int dailyTotalCap,
        int perVisitorDailyCap,
        String activityId,
        long createdAtUtc
) {
    public static CampaignDetailResponse from(Campaign campaign) {
        return new CampaignDetailResponse(
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.activityId(),
                campaign.createdAtUtc());
    }
}
