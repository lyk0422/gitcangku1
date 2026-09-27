package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Campaign;

/**
 * 公告视图。
 *
 * @param campaignId                公告编号
 * @param dailyTotalCap             每 UTC 日总额度，单位次
 * @param perVisitorDailyCap        每访客每 UTC 日上限，单位次
 * @param createdAtUtc              创建时刻，epoch 毫秒，UTC
 * @param attributionWindowMinutes  当前 UTC 归因窗口分钟数（要求值），1～1440，区间左闭右开
 * @param windowVersion             当前窗口版本号，从 1 起
 */
public record CampaignResponse(
        String campaignId,
        int dailyTotalCap,
        int perVisitorDailyCap,
        long createdAtUtc,
        int attributionWindowMinutes,
        int windowVersion
) {
    public static CampaignResponse from(Campaign campaign) {
        return new CampaignResponse(
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.createdAtUtc(),
                campaign.attributionWindowMinutes(),
                campaign.windowVersion());
    }
}
