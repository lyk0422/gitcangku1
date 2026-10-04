package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 创建配方版本请求。ingredients 为 2～6 种成分的质量百分比，
 * 每项为正值、十进制最多 4 位小数，合计必须精确等于 100；配方创建后不可修改。
 */
public record CreateRecipeRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "recipeKey 不能为空") String recipeKey,
        @NotNull(message = "ingredients 不能为空")
        @Size(min = 2, max = 6, message = "ingredients 必须包含 2～6 种成分")
        List<@Valid IngredientSpec> ingredients
) {

    /**
     * 成分规格：名称非空，质量百分比为正值且最多 4 位小数。
     */
    public record IngredientSpec(
            @NotBlank(message = "成分名称不能为空") String name,
            @NotNull(message = "percentage 不能为空") BigDecimal percentage
    ) {
    }
}