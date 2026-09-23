package com.example.starter.exposure.domain;

/**
 * 公告 PO。公告以 campaignId 唯一，创建时固定每 UTC 日总额度与每访客每日上限。
 *
 * @param campaignId          公告编号，全局唯一
 * @param dailyTotalCap       每 UTC 日总额度，单位次，取值 1～100000
 * @param perVisitorDailyCap  每访客每 UTC 日跨全部展示位共享上限，单位次，取值 1～100000
 * @param configVersion       展示位配置的递增版本号；创建公告为 1（随建 DEFAULT），
 *                            每新增一个展示位在同一事务内 +1，创建后只增不减
 * @param createdAtUtc        创建时刻（epoch 毫秒，UTC）
 */
public record Campaign(
        String campaignId,
        int dailyTotalCap,
        int perVisitorDailyCap,
        int configVersion,
        long createdAtUtc
) {
}
