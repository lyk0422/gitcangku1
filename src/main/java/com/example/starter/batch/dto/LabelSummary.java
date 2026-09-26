package com.example.starter.batch.dto;

/**
 * 标签核销汇总：plannedQuantity 为要求值（无计划时为 null），sealedQuantity 为实际值，
 * remainingQuantity 为差额（计划 - 实际，无计划时为 null）。
 */
public record LabelSummary(
        Integer plannedQuantity,
        int sealedQuantity,
        Integer remainingQuantity,
        int usedLabelCount,
        int activeSealCount
) {
}
