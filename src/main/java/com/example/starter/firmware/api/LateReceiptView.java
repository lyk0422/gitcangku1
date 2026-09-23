package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.CohortReceipt;

/**
 * 迟到回执证据视图：迁移提交后到达的旧代次回执，仅存档不改变统计。
 */
public record LateReceiptView(String deviceId, long cohortId, int generation, String result,
                              String receivedAtUtc) {

    public static LateReceiptView of(CohortReceipt receipt) {
        return new LateReceiptView(receipt.deviceId(), receipt.cohortId(), receipt.generation(),
                receipt.result().name(), receipt.receivedAtUtc());
    }
}
