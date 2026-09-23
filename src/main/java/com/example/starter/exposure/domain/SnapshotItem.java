package com.example.starter.exposure.domain;

/**
 * 撤回快照项 PO。撤回受理时冻结所有在途（RESERVED）预占的键、版本、visitorKey、
 * reservedAt 与 expiresAt；此后这些字段只读，决议只改变 decision。
 *
 * @param reservationId 预占单编号（快照项主键）
 * @param withdrawalKey 所属撤回键
 * @param campaignId    所属公告编号
 * @param campaignVersion 冻结的公告版本
 * @param visitorKey    冻结的访客键
 * @param reservedAtUtc 冻结的预占时刻（epoch 毫秒，UTC）
 * @param expiresAtUtc  冻结的到期时刻（epoch 毫秒，UTC）
 * @param decision      决议状态：PENDING / CONFIRMED / REJECTED
 * @param receiptKey    触发终态的回执键（唯一，回执路径写入）；非回执路径终态为 null
 * @param occurredAtUtc 回执声明的曝光发生时刻（epoch 毫秒，UTC）；非回执路径为 null
 * @param decidedAtUtc  进入终态时刻（epoch 毫秒，UTC），PENDING 为 null
 */
public record SnapshotItem(
        String reservationId,
        String withdrawalKey,
        String campaignId,
        int campaignVersion,
        String visitorKey,
        long reservedAtUtc,
        long expiresAtUtc,
        ItemDecision decision,
        String receiptKey,
        Long occurredAtUtc,
        Long decidedAtUtc
) {
}
