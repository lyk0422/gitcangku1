package com.example.starter.batch.dto;

import java.math.BigDecimal;

/**
 * 放行配方校验中单个成分的差额明细：expectedMassGrams 为目标总质量按声明占比折算的应称量，
 * actualMassGrams 为有效称量合计，massDifferenceGrams 为实际减期望（单位克）。
 * actualPercentage 仅为展示舍入到6位小数，判定使用未舍入值。
 */
public record IngredientDifference(
        String ingredient,
        BigDecimal declaredPercentage,
        BigDecimal expectedMassGrams,
        BigDecimal actualMassGrams,
        BigDecimal massDifferenceGrams,
        BigDecimal actualPercentage
) {
}