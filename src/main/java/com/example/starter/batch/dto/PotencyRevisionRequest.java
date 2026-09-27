package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 效价模型修订请求：只追加新版本（initialPotency/dailyDecayRate/minReleasePotency），
 * 锚点与数量不变；负值判 422。修订与检验/放行按事务提交顺序裁决，放行读取提交时的一致最新版本。
 */
public record PotencyRevisionRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "initialPotency 不能为空") BigDecimal initialPotency,
        @NotNull(message = "dailyDecayRate 不能为空") BigDecimal dailyDecayRate,
        @NotNull(message = "minReleasePotency 不能为空") BigDecimal minReleasePotency
) {
}
