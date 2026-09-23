package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.ExposureReceipt;
import com.example.starter.exposure.domain.SnapshotItemStatus;

/**
 * 回执决议视图。
 *
 * @param receiptKey     回执键
 * @param reservationId  对应预占单编号
 * @param occurredAtUtc  上报的曝光发生时刻，epoch 毫秒，UTC
 * @param decision       决议结果（CONFIRMED/REJECTED）
 * @param decidedAtUtc   决议（受理）时刻，epoch 毫秒，UTC
 */
public record ReceiptResponse(
        String receiptKey,
        String reservationId,
        long occurredAtUtc,
        SnapshotItemStatus decision,
        long decidedAtUtc
) {
    public static ReceiptResponse from(ExposureReceipt receipt) {
        return new ReceiptResponse(
                receipt.receiptKey(),
                receipt.reservationId(),
                receipt.occurredAtUtc(),
                receipt.decision(),
                receipt.createdAtUtc());
    }
}
