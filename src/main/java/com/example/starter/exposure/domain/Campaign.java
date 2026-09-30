package com.example.starter.exposure.domain;

/**
 * 公告 PO。公告以 campaignId 唯一，创建时固定每 UTC 日总额度与每访客每日上限。
 *
 * @param campaignId          公告编号，全局唯一
 * @param activityId          当前归属活动编号；null 表示未归入活动（仅有两级额度，不做小时匀速投放）。
 *                            活动迁移只影响迁移之后的新预占，既有预占固化申请时的活动归属
 * @param dailyTotalCap       每 UTC 日总额度，单位次，取值 1～100000
 * @param perVisitorDailyCap  每访客每 UTC 日上限，单位次，取值 1～100000
 * @param createdAtUtc        创建时刻（epoch 毫秒，UTC）
 */
public record Campaign(
        String campaignId,
        String activityId,
        int dailyTotalCap,
        int perVisitorDailyCap,
        long createdAtUtc
) {
}
