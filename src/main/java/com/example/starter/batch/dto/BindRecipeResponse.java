package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次配方绑定响应。stateVersion 为称量链版本号，绑定后为 0，每次称量或更正加 1。
 */
public record BindRecipeResponse(
        String batchKey,
        String recipeKey,
        Integer version,
        BigDecimal targetMass,
        Long stateVersion,
        Instant boundAt
) {
}
