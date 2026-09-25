package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Campaign;

/**
 * 公告视图。
 *
 * @param campaignId         公告编号
 * @param dailyTotalCap      每 UTC 日总额度，单位次
 * @param perVisitorDailyCap 每访客每 UTC 日上限，单位次
 * @param createdAtUtc       创建时刻，epoch 毫秒，UTC
 * @param category           活动类别；null 表示不启用同意裁决
 * @param version            活动版本号，初始 1，每次类别修改 +1
 * @param silentStartMinute  静默时段起点（UTC 日内分钟）；null 表示无静默
 * @param silentEndMinute    静默时段终点（UTC 日内分钟，左闭右开）；不大于起点表示跨午夜
 */
public record CampaignResponse(
        String campaignId,
        int dailyTotalCap,
        int perVisitorDailyCap,
        long createdAtUtc,
        String category,
        int version,
        Integer silentStartMinute,
        Integer silentEndMinute
) {
    public static CampaignResponse from(Campaign campaign) {
        return new CampaignResponse(
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.createdAtUtc(),
                campaign.category(),
                campaign.version(),
                campaign.silentStartMinute(),
                campaign.silentEndMinute());
    }
}
