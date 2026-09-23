package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Withdrawal;
import com.example.starter.exposure.domain.WithdrawalStatus;

import java.util.List;

/**
 * 撤回单视图：截点、状态与逐项决议快照。
 *
 * @param withdrawalKey   撤回键
 * @param campaignId      所属公告编号
 * @param campaignVersion 撤回时冻结的公告版本
 * @param cutoffAtUtc     撤回截点，epoch 毫秒，UTC
 * @param status          撤回状态：SETTLING / COMPLETED
 * @param createdAtUtc    撤回受理时刻，epoch 毫秒，UTC
 * @param completedAtUtc  全部快照项终态时刻，epoch 毫秒，UTC；未完成为 null
 * @param items           快照逐项决议（按预占单编号排序）
 */
public record WithdrawalResponse(
        String withdrawalKey,
        String campaignId,
        int campaignVersion,
        long cutoffAtUtc,
        WithdrawalStatus status,
        long createdAtUtc,
        Long completedAtUtc,
        List<SnapshotItemResponse> items
) {
    public static WithdrawalResponse of(Withdrawal withdrawal, List<SnapshotItemResponse> items) {
        return new WithdrawalResponse(
                withdrawal.withdrawalKey(),
                withdrawal.campaignId(),
                withdrawal.campaignVersion(),
                withdrawal.cutoffAtUtc(),
                withdrawal.status(),
                withdrawal.createdAtUtc(),
                withdrawal.completedAtUtc(),
                items);
    }
}
