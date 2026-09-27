package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 登记/修订批次效价衰减模型请求。三个效价参数为负时返回 422（业务前置条件），
 * 缺失时返回 400。每次成功登记产生新的模型版本，历史版本不改写。
 */
public record PotencyModelRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "initialPotency 不能为空") BigDecimal initialPotency,
        @NotNull(message = "minReleasePotency 不能为空") BigDecimal minReleasePotency,
        @NotNull(message = "decayRatePerDay 不能为空") BigDecimal decayRatePerDay
) {
}
