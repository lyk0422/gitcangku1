package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 配方版本响应。createdAt 为 UTC instant；成分按创建请求顺序返回。
 */
public record RecipeResponse(
        String recipeKey,
        List<Ingredient> ingredients,
        Instant createdAt
) {

    /**
     * 成分及其质量百分比。
     */
    public record Ingredient(String name, BigDecimal percentage) {
    }
}