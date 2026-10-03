package com.example.starter.exposure.domain;

/**
 * 公告当前所属活动。迁移只影响迁移后的新预占；既有预占单已固化活动与小时归属，不随之迁移。
 *
 * @param campaignId    公告编号，全局唯一
 * @param activityId    当前所属活动编号
 * @param assignedAtUtc 最近一次迁移（归入）时刻，epoch 毫秒，UTC
 */
public record CampaignActivity(
        String campaignId,
        String activityId,
        long assignedAtUtc
) {
}