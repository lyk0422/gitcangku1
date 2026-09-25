package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Campaign;

/**
 * 公告视图。
 *
 * @param campaignId         公告编号
 * @param dailyTotalCap      每 UTC 日总额度，单位次
 * @param perVisitorDailyCap 每访客每 UTC 日上限，单位次
 * @param category           活动类别
 * @param version            活动版本，初始 1，每次修改类别 +1
 * @param silenceStartSec    静默窗口起点（UTC 日第几秒，含）
 * @param silenceEndSec      静默窗口终点（UTC 日第几秒，不含）
 * @param minIntervalMillis  同访客两次有效曝光最小间隔，毫秒
 * @param createdAtUtc       创建时刻，epoch 毫秒，UTC
 */
public record CampaignResponse(
        String campaignId,
        int dailyTotalCap,
        int perVisitorDailyCap,
        String category,
        int version,
        int silenceStartSec,
        int silenceEndSec,
        long minIntervalMillis,
        long createdAtUtc
) {
    public static CampaignResponse from(Campaign campaign) {
        return new CampaignResponse(
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.category(),
                campaign.version(),
                campaign.silenceStartSec(),
                campaign.silenceEndSec(),
                campaign.minIntervalMillis(),
                campaign.createdAtUtc());
    }
}
