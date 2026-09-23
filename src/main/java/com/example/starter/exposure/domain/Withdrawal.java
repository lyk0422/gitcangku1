package com.example.starter.exposure.domain;

/**
 * 公告版本撤回单 PO。发布方以 withdrawalKey 撤回一个公告版本，
 * 撤回后该版本原子禁止新预占，已确认曝光不回退。
 *
 * @param withdrawalKey   撤回键，全局唯一
 * @param campaignId      所属公告编号
 * @param campaignVersion 被撤回的公告版本
 * @param cutoffAtUtc     撤回截点（epoch 毫秒，UTC）：仅 occurredAt 早于该截点的回执可确认
 * @param status          撤回状态
 * @param createdAtUtc    撤回创建时刻，epoch 毫秒，UTC
 * @param completedAtUtc  快照全部进入终态的时刻，未完成为 null
 */
public record Withdrawal(
        String withdrawalKey,
        String campaignId,
        int campaignVersion,
        long cutoffAtUtc,
        WithdrawalStatus status,
        long createdAtUtc,
        Long completedAtUtc
) {
}
