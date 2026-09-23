package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.SnapshotItemStatus;
import com.example.starter.exposure.domain.WithdrawalItem;

/**
 * 撤回快照项视图。
 *
 * @param reservationId   预占单编号（快照项键）
 * @param campaignVersion 冻结的公告版本
 * @param visitorId       冻结的访客键
 * @param reservedAtUtc   冻结的预占时刻，epoch 毫秒，UTC
 * @param expiresAtUtc    冻结的到期时刻，epoch 毫秒，UTC
 * @param status          快照项状态（SETTLING/CONFIRMED/REJECTED/EXPIRED）
 * @param decidedAtUtc    决议时刻，未决议为 null
 * @param decisionReason  决议来源，未决议为 null
 */
public record WithdrawalItemResponse(
        String reservationId,
        int campaignVersion,
        String visitorId,
        long reservedAtUtc,
        long expiresAtUtc,
        SnapshotItemStatus status,
        Long decidedAtUtc,
        String decisionReason
) {
    public static WithdrawalItemResponse from(WithdrawalItem item) {
        return new WithdrawalItemResponse(
                item.reservationId(),
                item.campaignVersion(),
                item.visitorId(),
                item.reservedAtUtc(),
                item.expiresAtUtc(),
                item.status(),
                item.decidedAtUtc(),
                item.decisionReason());
    }
}
