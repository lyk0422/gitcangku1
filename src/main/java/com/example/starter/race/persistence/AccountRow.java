package com.example.starter.race.persistence;

/**
 * runner_account 表行记录。
 *
 * @param accountId 账户ID，全局唯一
 * @param holderName 账户持有人名称（展示用）；null 表示未命名
 * @param balanceFen 可用余额，单位：分（1元=100分）；不含已冻结金额，永远非负
 * @param createdAt 账户创建时间，Unix毫秒时间戳
 * @param updatedAt 最近一次账务变动时间，Unix毫秒时间戳
 */
public record AccountRow(
        String accountId,
        String holderName,
        long balanceFen,
        long createdAt,
        long updatedAt
) {
}
