package com.example.starter.race.api;

import com.example.starter.race.domain.LedgerType;

/**
 * 保证金账务流水响应：冻结、退回或没收均展示为一笔固定正金额流水。
 *
 * @param ledgerId        流水自增主键
 * @param accountId       账户ID
 * @param protestId       关联申诉ID；RECHARGE 流水为 null
 * @param type            RECHARGE / FREEZE / REFUND / FORFEIT
 * @param amountFen       本笔固定金额，单位：分（1元=100分），恒为正；退款/没收恒等于申诉快照金额
 * @param balanceAfterFen 本笔落账后可用余额（不含冻结），单位：分
 * @param createdAt       流水生成时间，Unix毫秒时间戳
 */
public record LedgerResponse(
        long ledgerId,
        String accountId,
        String protestId,
        LedgerType type,
        long amountFen,
        long balanceAfterFen,
        long createdAt
) {
}
