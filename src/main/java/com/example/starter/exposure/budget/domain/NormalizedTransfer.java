package com.example.starter.exposure.budget.domain;

/**
 * 规范化后的预算转移明细：同一（源, 目标）对的数量已求和。
 *
 * @param sourceCampaignId 源活动编号
 * @param targetCampaignId 目标活动编号
 * @param amount           转移数量，正整数，单位次
 */
public record NormalizedTransfer(
        String sourceCampaignId,
        String targetCampaignId,
        long amount
) {
}
