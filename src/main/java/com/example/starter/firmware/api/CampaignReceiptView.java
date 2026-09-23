package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.CohortReceipt;

/**
 * 队列回执入账结果视图。
 */
public record CampaignReceiptView(long campaignId, String deviceId, long cohortId, int generation,
                                  String result, String disposition) {

    public static CampaignReceiptView of(CohortReceipt receipt) {
        return new CampaignReceiptView(receipt.campaignId(), receipt.deviceId(), receipt.cohortId(),
                receipt.generation(), receipt.result().name(), receipt.disposition().name());
    }
}
