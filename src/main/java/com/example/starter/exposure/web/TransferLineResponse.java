package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.TransferLine;

/**
 * 规范化预算转移明细视图。
 *
 * @param sourceCampaignId 源活动编号（预算转出方）
 * @param targetCampaignId 目标活动编号（预算转入方）
 * @param amount           转移数量，单位次，正整数
 */
public record TransferLineResponse(
        String sourceCampaignId,
        String targetCampaignId,
        int amount
) {
    public static TransferLineResponse from(TransferLine line) {
        return new TransferLineResponse(line.sourceCampaignId(), line.targetCampaignId(), line.amount());
    }
}
