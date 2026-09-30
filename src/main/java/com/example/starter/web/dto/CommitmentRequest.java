package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 用户承诺请求；承诺量单位 kWh，上限 50。
 *
 * @param userId       用户 ID
 * @param reductionKwh 承诺减少电量（kWh）
 * @param requestId    承诺幂等 requestId
 */
public record CommitmentRequest(
        @NotBlank String userId,
        @NotNull @DecimalMin(value = "0.0001", message = "承诺电量必须大于 0")
        @DecimalMax(value = "50", message = "承诺电量不得超过 50 kWh")
        BigDecimal reductionKwh,
        @NotBlank String requestId
) {
}
