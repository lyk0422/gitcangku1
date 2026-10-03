package com.example.starter.exposure.web;

/**
 * 公告-活动归属视图。
 *
 * @param campaignId         公告编号
 * @param activityId         迁移后所属活动编号
 * @param previousActivityId 迁移前所属活动编号；首次归入为 null
 * @param assignedAtUtc      本次迁移时刻，epoch 毫秒，UTC
 */
public record CampaignActivityResponse(
        String campaignId,
        String activityId,
        String previousActivityId,
        long assignedAtUtc
) {
}