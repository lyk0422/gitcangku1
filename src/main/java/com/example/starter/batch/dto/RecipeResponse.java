package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 配方版本响应。percentage 为质量百分比字符串（保留原始精度），createdAt 为 UTC instant。
 */
public record RecipeResponse(
        String recipeKey,
        List<IngredientEntry> ingredients,
        Instant createdAt
) {

    /**
     * 配方成分条目：name 成分名称，percentage 质量百分比。
     */
    public record IngredientEntry(String name, String percentage) {
    }
}
