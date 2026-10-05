package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Campaign;

/**
 * 公告视图。
 *
 * @param campaignId          公告编号
 * @param dailyTotalCap       每 UTC 日总额度，单位次
 * @param perVisitorDailyCap  每访客每 UTC 日上限，单位次
 * @param attentionWeight     固定注意力权重，仅新类型公告非空（1～5）；旧类型为 null
 * @param createdAtUtc        创建时刻，epoch 毫秒，UTC
 */
public record CampaignResponse(
        String campaignId,
        int dailyTotalCap,
        int perVisitorDailyCap,
        Integer attentionWeight,
        long createdAtUtc
) {
    public static CampaignResponse from(Campaign campaign) {
        return new CampaignResponse(
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.attentionWeight(),
                campaign.createdAtUtc());
    }
}
