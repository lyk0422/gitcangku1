package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 配方版本响应。createdAt 为 UTC instant。
 */
public record RecipeResponse(
        String recipeKey,
        List<Ingredient> ingredients,
        Instant createdAt
) {

    /**
     * 配方成分：name 成分名称，percentage 质量百分比（正值，最多 4 位小数）。
     */
    public record Ingredient(String name, BigDecimal percentage) {
    }
}