package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次配方绑定响应。weighingVersion 为当前称量链版本（绑定时为 0）；boundAt 为 UTC instant。
 */
public record BindRecipeResponse(
        String batchKey,
        String recipeKey,
        BigDecimal targetMass,
        long weighingVersion,
        Instant boundAt
) {
}