package com.example.starter.exposure.domain;

/**
 * 公告 PO。公告以 campaignId 唯一，创建时固定每 UTC 日总额度与每访客每日上限；
 * 冷却分钟数可在创建后携带 expectedVersion 修改，每次修改 version +1。
 *
 * @param campaignId          公告编号，全局唯一
 * @param dailyTotalCap       每 UTC 日总额度，单位次，取值 1～100000
 * @param perVisitorDailyCap  每访客每 UTC 日上限，单位次，取值 1～100000
 * @param cooldownMinutes     同一访客两次 CONFIRMED 曝光之间的最短冷却分钟数，
 *                            取值 0～1440，0 表示不限制；只影响修改后的新申请
 * @param version             冷却配置乐观锁版本号，初始 0，每次冷却配置修改 +1
 * @param createdAtUtc        创建时刻（epoch 毫秒，UTC）
 */
public record Campaign(
        String campaignId,
        int dailyTotalCap,
        int perVisitorDailyCap,
        int cooldownMinutes,
        long version,
        long createdAtUtc
) {
}
