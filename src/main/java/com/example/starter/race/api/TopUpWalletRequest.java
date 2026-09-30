package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 选手余额充值请求；充值为合成数据入口，金额为人民币元正整数，直接增加可用余额。
 * 参赛号以路径参数为准。
 *
 * @param transactionId   充值流水ID，全局唯一（第二层幂等键；同ID重放原结果，异参409）
 * @param amount          充值金额（人民币元，正整数，1~1000000）
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record TopUpWalletRequest(
        @NotBlank String transactionId,
        @NotNull @Positive @Max(1_000_000) Integer amount,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
