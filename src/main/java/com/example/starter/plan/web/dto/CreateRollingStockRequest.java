package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 登记车底请求。requestKey 为幂等键；最小周转分钟数 1～240。
 */
public record CreateRollingStockRequest(
        @NotBlank String requestKey,
        @NotBlank String stockKey,
        @NotNull @Min(1) @Max(240) Integer minTurnaroundMinutes) {
}
