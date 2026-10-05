package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 批次绑定配方请求。recipeKey 为不可变配方版本；targetMass 为目标总质量，
 * 单位克，正值且最多 6 位小数。每批次至多绑定一次，绑定后不可修改。
 */
public record BindRecipeRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "recipeKey 不能为空") String recipeKey,
        @NotNull(message = "targetMass 不能为空") BigDecimal targetMass
) {
}