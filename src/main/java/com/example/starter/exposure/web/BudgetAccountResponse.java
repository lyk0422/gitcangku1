package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.BudgetAccount;

/**
 * 活动预算账本视图。恒等式：budget = transferable + inFlight + confirmed。
 *
 * @param campaignId     活动编号
 * @param tenantId       租户编号
 * @param windowStartUtc 投放窗口起始时刻（含），epoch 毫秒，UTC
 * @param windowEndUtc   投放窗口结束时刻（不含），epoch 毫秒，UTC
 * @param audienceRule   受众规则标识
 * @param budget         当前总预算，单位次
 * @param inFlight       在途预占数，单位次
 * @param confirmed      已确认曝光数，单位次
 * @param transferable   可转余额（只计算尚未预占的预算），单位次
 * @param version        乐观锁版本号
 */
public record BudgetAccountResponse(
        String campaignId,
        String tenantId,
        long windowStartUtc,
        long windowEndUtc,
        String audienceRule,
        int budget,
        int inFlight,
        int confirmed,
        int transferable,
        int version
) {
    public static BudgetAccountResponse from(BudgetAccount account) {
        return new BudgetAccountResponse(
                account.campaignId(),
                account.tenantId(),
                account.windowStartUtc(),
                account.windowEndUtc(),
                account.audienceRule(),
                account.budget(),
                account.inFlight(),
                account.confirmed(),
                account.transferable(),
                account.version());
    }
}
