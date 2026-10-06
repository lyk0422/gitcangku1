package com.example.starter.batch.dto;

import java.math.BigDecimal;

/**
 * 单成分投料对照。expectedMass 为按配方百分比与目标总质量折算的期望质量（克）；
 * actualMass 为当前有效称量累计（克），未称量为 null；massDifference 为实际减期望（克）；
 * deviationPoints 为实际占比与目标占比的偏差（百分点，仅展示用，判断不依赖该舍入值）；
 * withinTolerance 表示偏差不超过 0.1 个百分点（未称量为 false）。
 */
public record IngredientTotal(
        String ingredient,
        BigDecimal targetPercentage,
        BigDecimal expectedMass,
        BigDecimal actualMass,
        BigDecimal massDifference,
        BigDecimal deviationPoints,
        boolean weighed,
        boolean withinTolerance
) {
}
