package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 批次绑定配方请求。targetMass 为目标总质量，单位克，正值、最多 6 位小数；
 * 绑定后配方版本与目标总质量均不可修改。
 */
public record BindRecipeRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "recipeKey 不能为空") String recipeKey,
        @NotNull(message = "version 不能为空") Integer version,
        @NotNull(message = "targetMass 不能为空") BigDecimal targetMass
) {
}
