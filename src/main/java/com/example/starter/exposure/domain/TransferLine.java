package com.example.starter.exposure.domain;

/**
 * 规范化预算转移明细。按（源活动, 目标活动）求和后得到，amount 为正整数。
 *
 * @param sourceCampaignId 源活动编号（预算转出方）
 * @param targetCampaignId 目标活动编号（预算转入方）
 * @param amount           转移数量，单位次，正整数
 */
public record TransferLine(
        String sourceCampaignId,
        String targetCampaignId,
        int amount
) implements Comparable<TransferLine> {

    /** 稳定排序：先源后目标，字典序。 */
    @Override
    public int compareTo(TransferLine other) {
        int bySource = sourceCampaignId.compareTo(other.sourceCampaignId);
        return bySource != 0 ? bySource : targetCampaignId.compareTo(other.targetCampaignId);
    }
}
