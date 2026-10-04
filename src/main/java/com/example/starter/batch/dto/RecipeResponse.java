package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 配方版本响应：成分按创建顺序返回，百分比保留原始精度。
 */
public record RecipeResponse(
        String recipeKey,
        List<IngredientView> ingredients,
        Instant createdAt
) {

    /**
     * 成分视图：名称 + 质量百分比。
     */
    public record IngredientView(String name, BigDecimal percentage) {
    }
}