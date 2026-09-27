package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Campaign;

/**
 * 公告视图。
 *
 * @param campaignId                公告编号
 * @param dailyTotalCap             每 UTC 日总额度，单位次
 * @param perVisitorDailyCap        每访客每 UTC 日上限，单位次
 * @param attributionWindowMinutes  当前生效的 UTC 归因窗口，单位分钟
 * @param windowVersion             当前归因窗口版本号
 * @param createdAtUtc              创建时刻，epoch 毫秒，UTC
 */
public record CampaignResponse(
        String campaignId,
        int dailyTotalCap,
        int perVisitorDailyCap,
        int attributionWindowMinutes,
        int windowVersion,
        long createdAtUtc
) {
    public static CampaignResponse from(Campaign campaign) {
        return new CampaignResponse(
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.attributionWindowMinutes(),
                campaign.windowVersion(),
                campaign.createdAtUtc());
    }
}
