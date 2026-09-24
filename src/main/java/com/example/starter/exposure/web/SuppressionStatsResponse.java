package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.CampaignCategory;

import java.time.LocalDate;

/**
 * 抑制统计视图：按公告、访客与 UTC 日累计被静默抑制的申请次数。
 *
 * @param campaignId 公告编号
 * @param visitorId  访客编号
 * @param category   公告类别（CRITICAL/SERVICE/MARKETING）
 * @param utcDate    统计的 UTC 日，格式 yyyy-MM-dd
 * @param count      当日该公告对该访客的抑制累计次数，单位次；无记录为 0
 */
public record SuppressionStatsResponse(
        String campaignId,
        String visitorId,
        CampaignCategory category,
        LocalDate utcDate,
        long count
) {
}
