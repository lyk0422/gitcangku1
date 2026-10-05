package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 配方版本响应：成分按创建顺序返回，百分比单位 %，最多 4 位小数。
 */
public record RecipeResponse(
        String recipeKey,
        int version,
        List<IngredientEntry> ingredients,
        Instant createdAt
) {

    /**
     * 成分条目：name 成分名称，percentage 质量百分比。
     */
    public record IngredientEntry(String name, BigDecimal percentage) {
    }
}
