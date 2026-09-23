package com.example.starter.exposure.domain;

/**
 * 公告版本撤回单 PO。withdrawalKey 全局唯一；撤回成功后该公告原子禁止新预占。
 *
 * @param withdrawalKey 撤回键，全局唯一；同键同参重放原结果，异参 409
 * @param campaignId    所属公告编号
 * @param campaignVersion 撤回时提交并校验的公告当前版本，与快照项冻结版本一致
 * @param cutoffAtUtc   撤回截点（epoch 毫秒，UTC）；仅 occurredAt 早于该值的回执才可能合法确认
 * @param status        撤回状态：SETTLING 结算中 / COMPLETED 全部快照项终态
 * @param createdAtUtc  撤回受理时刻（epoch 毫秒，UTC）
 * @param completedAtUtc 全部快照项终态时刻（epoch 毫秒，UTC），未完成为 null
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
