package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.AccountSnapshot;

/**
 * 活动账本状态视图：用于预览后态与转移前后快照。
 * 恒等式：budget = transferable + inFlight + confirmed。
 *
 * @param campaignId   活动编号
 * @param budget       总预算，单位次
 * @param inFlight     在途预占数，单位次
 * @param confirmed    已确认曝光数，单位次
 * @param transferable 可转余额（只计算尚未预占的预算），单位次
 * @param version      账本版本号
 */
public record AccountStateResponse(
        String campaignId,
        int budget,
        int inFlight,
        int confirmed,
        int transferable,
        int version
) {
    public static AccountStateResponse from(AccountSnapshot snapshot) {
        return new AccountStateResponse(
                snapshot.campaignId(),
                snapshot.budget(),
                snapshot.inFlight(),
                snapshot.confirmed(),
                snapshot.transferable(),
                snapshot.version());
    }
}
