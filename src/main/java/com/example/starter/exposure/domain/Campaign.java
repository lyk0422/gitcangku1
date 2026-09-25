package com.example.starter.exposure.domain;

/**
 * 公告 PO。公告以 campaignId 唯一，创建时固定每 UTC 日总额度与每访客每日上限。
 *
 * @param campaignId          公告编号，全局唯一
 * @param dailyTotalCap       每 UTC 日总额度，单位次，取值 1～100000
 * @param perVisitorDailyCap  每访客每 UTC 日上限，单位次，取值 1～100000
 * @param cooldownMinutes     同一访客两次 CONFIRMED 之间的最短冷却分钟数，0～1440，0 表示不限制
 * @param version             公告配置乐观版本号，从 0 起，冷却配置每次修改 +1
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
