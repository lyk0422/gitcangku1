package com.example.starter.exposure.budget.domain;

/**
 * 单个活动在某一时刻的预算账本视图。恒等式：budget = transferable + inflight + confirmed。
 *
 * @param campaignId   活动编号
 * @param version      账本版本号
 * @param budget       总预算，单位次
 * @param confirmed    已确认曝光数，不可回收，单位次
 * @param inflight     在途预占数（已预占但尚未回执或过期），单位次
 * @param transferable 可转余额 = budget - inflight - confirmed，单位次
 */
public record CampaignLedger(
        String campaignId,
        long version,
        long budget,
        long confirmed,
        long inflight,
        long transferable
) {
}
