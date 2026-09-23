package com.example.starter.exposure.budget.web;

import com.example.starter.exposure.budget.domain.CampaignLedger;

/**
 * 单个活动的账本后态视图：总预算、已确认、在途预占、可转余额。
 * 恒等式：budget = transferable + inflight + confirmed。
 *
 * @param campaignId   活动编号
 * @param version      账本版本号
 * @param budget       总预算，单位次
 * @param confirmed    已确认曝光数，单位次
 * @param inflight     在途预占数（未回执未过期），单位次
 * @param transferable 可转余额，单位次
 */
public record CampaignLedgerResponse(
        String campaignId,
        long version,
        long budget,
        long confirmed,
        long inflight,
        long transferable
) {
    public static CampaignLedgerResponse from(CampaignLedger ledger) {
        return new CampaignLedgerResponse(
                ledger.campaignId(),
                ledger.version(),
                ledger.budget(),
                ledger.confirmed(),
                ledger.inflight(),
                ledger.transferable());
    }
}
