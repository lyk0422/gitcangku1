package com.example.starter.billing.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 用户承诺减量请求；每个用户对每个邀约至多一个有效承诺，承诺量上限 50 kWh。
 *
 * @param userId       用户 ID
 * @param committedKwh 承诺减少用电量，单位 kWh，(0, 50]
 * @param requestId    承诺幂等键，同参重放原结果，异参返回 409
 */
public record CreateCommitmentRequest(
        @NotBlank(message = "用户ID不能为空")
        String userId,
        @NotNull(message = "承诺电量不能为空")
        BigDecimal committedKwh,
        @NotBlank(message = "requestId不能为空")
        String requestId) {
}
