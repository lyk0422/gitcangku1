package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 配方绑定响应。
 */
public record BindingResponse(
        String batchKey,
        String recipeKey,
        int recipeVersion,
        BigDecimal targetMass,
        long weighingVersion,
        Instant createdAt
) {
}
