package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 配方版本响应。ingredients 按创建顺序给出各成分质量百分比（最多 4 位小数）。
 */
public record RecipeResponse(
        String recipeKey,
        Integer version,
        List<IngredientEntry> ingredients,
        Instant createdAt
) {

    /**
     * 成分定义：name 成分名称，percentage 质量百分比。
     */
    public record IngredientEntry(String name, BigDecimal percentage) {
    }
}
