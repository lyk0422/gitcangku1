package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批次称量状态：目标总质量、当前有效总量、称量链版本、是否满足配方，
 * 以及各成分声明百分比、需求量、实际量、差额与占比偏差。
 * difference = actualMass - requiredMass（单位克）；deviationPoints 为实际占比与
 * 声明百分比的偏差（百分点，仅用于展示，判定使用未舍入的精确比较）。
 */
public record WeighingStatusResponse(
        String batchKey,
        String recipeKey,
        BigDecimal targetMass,
        BigDecimal totalMass,
        long weighingVersion,
        boolean satisfied,
        List<IngredientStatus> ingredients
) {

    /**
     * 单成分称量状态。actualMass 为 null 表示该成分尚无有效称量。
     */
    public record IngredientStatus(
            String ingredient,
            BigDecimal declaredPercentage,
            BigDecimal requiredMass,
            BigDecimal actualMass,
            BigDecimal difference,
            BigDecimal deviationPoints,
            boolean withinTolerance
    ) {
    }
}