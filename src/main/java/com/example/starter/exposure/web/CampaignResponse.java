package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Campaign;

import java.util.List;

/**
 * 公告视图。
 *
 * @param campaignId          公告编号
 * @param dailyTotalCap       每 UTC 日总额度，单位次
 * @param perVisitorDailyCap  每访客每 UTC 日上限，单位次
 * @param creativeIds         有序轮换创意编号；空列表表示不支持轮换申请
 * @param createdAtUtc        创建时刻，epoch 毫秒，UTC
 */
public record CampaignResponse(
        String campaignId,
        int dailyTotalCap,
        int perVisitorDailyCap,
        List<String> creativeIds,
        long createdAtUtc
) {
    public static CampaignResponse from(Campaign campaign) {
        return new CampaignResponse(
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.creativeIds(),
                campaign.createdAtUtc());
    }
}
