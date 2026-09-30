package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 资金流水响应：充值、冻结、退回或没收的固定金额记录。
 *
 * @param transactionId 流水ID
 * @param raceId        赛事ID
 * @param bib           参赛号
 * @param protestId     关联申诉ID；充值流水为 null
 * @param type          流水类型：TOPUP / FREEZE / REFUND / FORFEIT
 * @param amount        固定金额（人民币元，正整数）
 * @param balanceAfter  记账后可用余额（人民币元，非负整数）
 * @param createdAt     记账时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WalletTransactionResponse(
        String transactionId,
        String raceId,
        String bib,
        String protestId,
        String type,
        int amount,
        int balanceAfter,
        long createdAt
) {
}
