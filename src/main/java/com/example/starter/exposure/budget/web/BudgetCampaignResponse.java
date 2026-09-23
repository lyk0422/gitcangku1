package com.example.starter.exposure.budget.web;

import com.example.starter.exposure.budget.domain.BudgetCampaign;

/**
 * 活动预算账本视图。
 *
 * @param campaignId     活动编号
 * @param tenantId       租户编号
 * @param windowStartUtc 窗口起点（含），epoch 毫秒，UTC
 * @param windowEndUtc   窗口终点（不含），epoch 毫秒，UTC
 * @param audienceRule   受众规则规范化串
 * @param budget         总预算，单位次
 * @param version        账本版本号
 * @param createdAtUtc   创建时刻，epoch 毫秒，UTC
 */
public record BudgetCampaignResponse(
        String campaignId,
        String tenantId,
        long windowStartUtc,
        long windowEndUtc,
        String audienceRule,
        long budget,
        long version,
        long createdAtUtc
) {
    public static BudgetCampaignResponse from(BudgetCampaign c) {
        return new BudgetCampaignResponse(
                c.campaignId(),
                c.tenantId(),
                c.windowStartUtc(),
                c.windowEndUtc(),
                c.audienceRule(),
                c.budget(),
                c.version(),
                c.createdAtUtc());
    }
}
