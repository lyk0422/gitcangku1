package com.example.starter.exposure.domain;

/**
 * 撤回快照项 PO：撤回时刻对一条 PENDING(RESERVED) 预占的冻结拷贝。
 * 键、版本、visitorKey、reservedAt 与 expiresAt 冻结后不再变化；
 * 决议结果回写到对应预占单（CONFIRMED/REJECTED/EXPIRED）。
 *
 * @param withdrawalKey   所属撤回键
 * @param reservationId   预占单编号（快照项键）
 * @param campaignVersion 冻结的公告版本
 * @param visitorId       冻结的访客键
 * @param reservedAtUtc   冻结的预占时刻，epoch 毫秒，UTC
 * @param expiresAtUtc    冻结的到期时刻，epoch 毫秒，UTC
 * @param status          快照项状态
 * @param decidedAtUtc    进入终态（决议）的时刻，未决议为 null
 * @param decisionReason  决议来源（RECEIPT_CONFIRMED/RECEIPT_REJECTED/SETTLED_EXPIRED/
 *                        SETTLED_NO_VALID_OCCURRENCE/EXPIRED），未决议为 null
 */
public record WithdrawalItem(
        String withdrawalKey,
        String reservationId,
        int campaignVersion,
        String visitorId,
        long reservedAtUtc,
        long expiresAtUtc,
        SnapshotItemStatus status,
        Long decidedAtUtc,
        String decisionReason
) {
}
