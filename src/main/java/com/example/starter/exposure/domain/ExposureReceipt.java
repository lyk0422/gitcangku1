package com.example.starter.exposure.domain;

/**
 * 曝光回执 PO。receiptKey 全局唯一；同键同参重放返回原决议，异参 409。
 *
 * @param receiptKey     回执键，全局唯一
 * @param reservationId  对应预占单编号
 * @param occurredAtUtc  客户端上报的曝光发生时刻，epoch 毫秒，UTC
 * @param decision       决议结果（CONFIRMED/REJECTED）
 * @param createdAtUtc   回执受理时刻，epoch 毫秒，UTC
 */
public record ExposureReceipt(
        String receiptKey,
        String reservationId,
        long occurredAtUtc,
        SnapshotItemStatus decision,
        long createdAtUtc
) {
}
