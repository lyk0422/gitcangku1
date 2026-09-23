package com.example.starter.exposure.budget.web;

import java.util.List;

/**
 * 预算转移激活结果：转移单键、规范化明细与全部端点活动的后态账本。
 *
 * @param transferKey 转移单业务唯一键
 * @param requestId   激活请求幂等键
 * @param details     冻结的规范化明细（源,目标,数量，稳定排序）
 * @param ledgers     全部端点活动的后态账本，按 campaignId 升序
 */
public record BudgetTransferActivateResponse(
        String transferKey,
        String requestId,
        List<FrozenDetail> details,
        List<CampaignLedgerResponse> ledgers
) {
    /**
     * 冻结明细视图。
     *
     * @param sourceCampaignId 源活动编号
     * @param targetCampaignId 目标活动编号
     * @param amount           转移数量，单位次
     */
    public record FrozenDetail(String sourceCampaignId, String targetCampaignId, long amount) {
    }
}
