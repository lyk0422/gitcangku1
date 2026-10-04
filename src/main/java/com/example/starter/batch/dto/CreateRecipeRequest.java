package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 创建配方版本请求。配方版本不可变：2至6种成分，质量百分比每项正值、最多4位小数，
 * 合计精确等于100。requestId 为幂等键，同键同参重放首次响应，同键异参返回409。
 */
public record CreateRecipeRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "recipeKey 不能为空") String recipeKey,
        @NotNull(message = "ingredients 不能为空")
        @Size(min = 2, max = 6, message = "ingredients 必须包含 2至6 种成分")
        List<@Valid IngredientSpec> ingredients
) {

    /**
     * 成分声明：name 为成分名称，percentage 为质量百分比（最多4位小数的正数）。
     */
    public record IngredientSpec(
            @NotBlank(message = "成分名称不能为空") String name,
            @NotNull(message = "percentage 不能为空") BigDecimal percentage
    ) {
    }
}