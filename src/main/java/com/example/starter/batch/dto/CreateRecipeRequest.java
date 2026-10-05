package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 创建配方版本请求。ingredients 为 2～6 种成分；每种成分质量百分比为正值、
 * 最多 4 位小数，且全部成分合计精确等于 100。配方版本创建后不可修改。
 */
public record CreateRecipeRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "recipeKey 不能为空") String recipeKey,
        @NotNull(message = "ingredients 不能为空")
        @Size(min = 2, max = 6, message = "ingredients 必须包含 2～6 种成分")
        List<@Valid IngredientSpec> ingredients
) {

    /**
     * 成分规格：name 为成分名称（同一配方内唯一），percentage 为质量百分比。
     */
    public record IngredientSpec(
            @NotBlank(message = "成分名称不能为空") String name,
            @NotNull(message = "percentage 不能为空") BigDecimal percentage
    ) {
    }
}