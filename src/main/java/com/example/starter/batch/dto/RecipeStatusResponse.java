package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批次配方状态：绑定信息、各成分目标/实际/差额与整体是否满足配方。
 * 质量单位克；百分比单位 %；差额 = 实际 - 目标（负值为不足）。
 */
public record RecipeStatusResponse(
        String batchKey,
        String recipeKey,
        int recipeVersion,
        BigDecimal targetMass,
        long weighingVersion,
        BigDecimal totalMass,
        boolean satisfied,
        List<IngredientStatus> ingredients
) {

    /**
     * 成分状态：requiredMass 为按目标总质量折算的目标质量，actualMass 为有效称量累计，
     * massDifference 为质量差额（实际-目标），percentageDeviation 为实际占比与配方占比之差（百分点），
     * weighed 表示该成分是否已有有效称量，withinTolerance 表示占比偏差是否不超过 0.1 个百分点。
     */
    public record IngredientStatus(
            String name,
            BigDecimal requiredPercentage,
            BigDecimal requiredMass,
            BigDecimal actualMass,
            BigDecimal massDifference,
            BigDecimal percentageDeviation,
            boolean weighed,
            boolean withinTolerance
    ) {
    }
}
