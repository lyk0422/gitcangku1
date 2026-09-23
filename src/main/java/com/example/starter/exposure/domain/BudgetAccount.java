package com.example.starter.exposure.domain;

/**
 * 活动预算账本 PO。每个 campaign 在左闭右开 UTC 投放窗口内持有非负整数预算。
 *
 * <p>恒等式：{@code budget = 可转余额 + inFlight + confirmed}，由表级 CHECK 约束保证；
 * 可转余额 = budget - inFlight - confirmed，只计算尚未预占的预算。</p>
 *
 * @param campaignId     活动编号，全局唯一
 * @param tenantId       租户编号；预算转移要求双方同租户
 * @param windowStartUtc 投放窗口起始时刻（含），epoch 毫秒，UTC
 * @param windowEndUtc   投放窗口结束时刻（不含），epoch 毫秒，UTC
 * @param audienceRule   受众规则标识；预算转移要求双方一致
 * @param budget         当前总预算，单位次，非负整数
 * @param inFlight       在途预占数（已预占未回执未过期释放），单位次
 * @param confirmed      已确认曝光数，单位次，不可回收
 * @param version        乐观锁版本号，每次成功预算转移后 +1
 * @param createdAtUtc   账本创建时刻，epoch 毫秒，UTC
 */
public record BudgetAccount(
        String campaignId,
        String tenantId,
        long windowStartUtc,
        long windowEndUtc,
        String audienceRule,
        int budget,
        int inFlight,
        int confirmed,
        int version,
        long createdAtUtc
) {
    /** 可转余额：只计算尚未预占的预算。 */
    public int transferable() {
        return budget - inFlight - confirmed;
    }
}
