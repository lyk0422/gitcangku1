package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.ItemDecision;
import com.example.starter.exposure.domain.SnapshotItem;

/**
 * 撤回快照项视图。冻结字段只读，decision 为逐项决议。
 *
 * @param reservationId   预占单编号
 * @param campaignVersion 冻结的公告版本
 * @param visitorKey      冻结的访客键
 * @param reservedAtUtc   冻结的预占时刻，epoch 毫秒，UTC
 * @param expiresAtUtc    冻结的到期时刻，epoch 毫秒，UTC
 * @param decision        决议状态：PENDING / CONFIRMED / REJECTED
 * @param receiptKey      触发终态的回执键；非回执路径为 null
 * @param occurredAtUtc   回执声明的曝光发生时刻，epoch 毫秒，UTC；非回执路径为 null
 * @param decidedAtUtc    进入终态时刻，epoch 毫秒，UTC；PENDING 为 null
 */
public record SnapshotItemResponse(
        String reservationId,
        int campaignVersion,
        String visitorKey,
        long reservedAtUtc,
        long expiresAtUtc,
        ItemDecision decision,
        String receiptKey,
        Long occurredAtUtc,
        Long decidedAtUtc
) {
    public static SnapshotItemResponse from(SnapshotItem item) {
        return new SnapshotItemResponse(
                item.reservationId(),
                item.campaignVersion(),
                item.visitorKey(),
                item.reservedAtUtc(),
                item.expiresAtUtc(),
                item.decision(),
                item.receiptKey(),
                item.occurredAtUtc(),
                item.decidedAtUtc());
    }
}
