package com.example.starter.exposure.domain;

/**
 * 活动 PO。活动按 UTC 自然日配置总确认额度与 24 个小时预算（小时预算另存
 * activity_hour_budget 表），公告归入活动后按小时匀速投放。
 *
 * @param activityId      活动编号，全局唯一
 * @param dailyConfirmCap 每 UTC 日总确认额度，单位次，取值 1~100000；
 *                        等于已配置 24 个小时预算之和
 * @param version         配置版本，从 1 开始，每次成功修改 +1；修改须携带 expectedVersion
 * @param createdAtUtc    创建时刻，epoch 毫秒，UTC
 */
public record Activity(
        String activityId,
        int dailyConfirmCap,
        long version,
        long createdAtUtc
) {
}
