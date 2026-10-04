package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次配方绑定响应：绑定的配方版本与目标总质量（克）。
 */
public record RecipeBindingResponse(
        String batchKey,
        String recipeKey,
        BigDecimal targetMassGrams,
        String boundBy,
        Instant createdAt
) {
}