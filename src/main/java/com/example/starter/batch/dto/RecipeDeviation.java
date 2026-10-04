package com.example.starter.batch.dto;

import java.math.BigDecimal;

/**
 * 单成分配方偏差：目标/实际百分比与质量（克），differenceGrams 为实际量减目标量。
 * 占比按高精度计算，不提前舍入。
 */
public record RecipeDeviation(
        String ingredient,
        BigDecimal targetPercentage,
        BigDecimal actualPercentage,
        BigDecimal targetMassGrams,
        BigDecimal actualMassGrams,
        BigDecimal differenceGrams
) {
}