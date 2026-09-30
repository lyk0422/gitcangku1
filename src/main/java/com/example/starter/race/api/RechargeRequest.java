package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 账户充值请求：金额以分为单位（1元=100分），必须为正整数。
 *
 * @param amountFen 充值金额，单位：分
 * @param requestId 全局唯一请求ID（幂等键）
 */
public record RechargeRequest(
        @NotNull @Positive Long amountFen,
        @NotBlank String requestId
) {
}
