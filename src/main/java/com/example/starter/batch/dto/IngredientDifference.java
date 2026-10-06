package com.example.starter.batch.dto;

/**
 * 配方门禁的单成分差额。expectedMassG 为按目标总质量折算的期望质量；
 * actualMassG 为有效称量合计；differenceMassG = actualMassG - expectedMassG；
 * withinTolerance 表示该成分占比偏差是否在 0.1 个百分点内。质量单位均为克。
 */
public record IngredientDifference(
        String ingredient,
        String recipePct,
        String expectedMassG,
        String actualMassG,
        String differenceMassG,
        boolean withinTolerance
) {
}
