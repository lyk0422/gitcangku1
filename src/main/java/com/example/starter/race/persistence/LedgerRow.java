package com.example.starter.race.persistence;

import com.example.starter.race.domain.LedgerType;

/**
 * protest_deposit_ledger 表行记录：保证金账务流水。
 *
 * @param ledgerId 自增主键
 * @param accountId 账务账户ID
 * @param protestId 关联申诉ID；RECHARGE 流水为 null
 * @param type 流水类型：RECHARGE / FREEZE / REFUND / FORFEIT
 * @param amountFen 本笔流水固定金额，单位：分，恒为正数；退款/没收恒等于申诉快照金额
 * @param balanceAfterFen 本笔落账后的可用余额（不含冻结），单位：分
 * @param createdAt 流水生成时间，Unix毫秒时间戳
 */
public record LedgerRow(
        long ledgerId,
        String accountId,
        String protestId,
        LedgerType type,
        long amountFen,
        long balanceAfterFen,
        long createdAt
) {
}
