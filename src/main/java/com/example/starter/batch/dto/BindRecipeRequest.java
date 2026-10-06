package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 批次绑定配方请求。批次绑定不可变配方版本及目标总质量（单位克，正值，最多 6 位小数）；
 * 每批最多绑定一次，绑定后不可修改。
 */
public record BindRecipeRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "recipeKey 不能为空") String recipeKey,
        @NotNull(message = "version 不能为空") Integer version,
        @NotNull(message = "targetMass 不能为空") BigDecimal targetMass
) {
}
