package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 账户信息响应。
 *
 * @param accountId  账户ID
 * @param holderName 账户持有人名称；未命名为 null
 * @param balanceFen 可用余额，单位：分（1元=100分）；不含已冻结金额
 * @param createdAt  账户创建时间，Unix毫秒时间戳
 * @param updatedAt  最近一次账务变动时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountResponse(
        String accountId,
        String holderName,
        long balanceFen,
        long createdAt,
        long updatedAt
) {
}
