package com.example.starter.exposure.budget.domain;

/**
 * 活动预算账本 PO。每个 campaign 在一个左闭右开 UTC 投放窗口
 * [{@link #windowStartUtc}, {@link #windowEndUtc}) 内拥有非负整数 {@link #budget}。
 *
 * <p>预算恒等式对每个活动成立：总预算 budget = 可用 transferable + 在途 inflight + 已确认 confirmed。
 * 每次预算转移对端点活动逐活动 {@link #version} +1，用于 expectedVersion 乐观校验。</p>
 *
 * @param campaignId     活动编号，全局唯一
 * @param tenantId       租户编号，仅同租户活动间可转移
 * @param windowStartUtc 投放窗口起点（含），epoch 毫秒，UTC
 * @param windowEndUtc   投放窗口终点（不含），epoch 毫秒，UTC
 * @param audienceRule   受众规则规范化串，仅相等的活动间可转移
 * @param budget         活动总预算，非负整数，单位次
 * @param version        账本版本号，初始为 1，每次涉及该活动的转移 +1
 * @param createdAtUtc   创建时刻，epoch 毫秒，UTC
 */
public record BudgetCampaign(
        String campaignId,
        String tenantId,
        long windowStartUtc,
        long windowEndUtc,
        String audienceRule,
        long budget,
        long version,
        long createdAtUtc
) {
}
