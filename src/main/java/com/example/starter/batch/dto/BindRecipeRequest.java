package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 批次绑定配方请求：绑定不可变配方版本及目标总质量（单位克，最多 6 位小数，正值）。
 * 操作人通过 X-Actor-Id 请求头提供；一批仅可绑定一次。
 */
public record BindRecipeRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "recipeKey 不能为空") String recipeKey,
        @NotNull(message = "targetMassGrams 不能为空") BigDecimal targetMassGrams
) {
}