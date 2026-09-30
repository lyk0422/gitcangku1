package com.example.starter.race.persistence;

import com.example.starter.race.domain.WalletTransactionType;

/**
 * wallet_transaction 表行记录：充值/冻结/退回/没收资金流水，金额固定不可变。
 *
 * @param transactionId 流水ID，全局唯一
 * @param raceId        所属赛事ID
 * @param bib           选手参赛号
 * @param protestId     关联申诉ID；TOPUP 时为 null
 * @param type          流水类型：TOPUP / FREEZE / REFUND / FORFEIT
 * @param amount        固定金额（人民币元，正整数）
 * @param balanceAfter  记账后选手可用余额（人民币元，非负整数）；FORFEIT 不改变余额，记录没收时余额
 * @param createdAt     记账时间，Unix毫秒时间戳
 */
public record WalletTransactionRow(
        String transactionId,
        String raceId,
        String bib,
        String protestId,
        WalletTransactionType type,
        int amount,
        int balanceAfter,
        long createdAt
) {
}
