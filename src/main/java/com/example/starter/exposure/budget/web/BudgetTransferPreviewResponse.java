package com.example.starter.exposure.budget.web;

import java.util.List;

/**
 * 预算转移预览结果：按完整后态返回各活动账本，稳定排序，不写任何数据。
 *
 * @param ledgers 参与转移活动的后态账本列表，按 campaignId 升序
 */
public record BudgetTransferPreviewResponse(
        List<CampaignLedgerResponse> ledgers
) {
}
