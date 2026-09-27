package com.example.starter.batch.dto;

import java.util.List;

/**
 * 标签核销诊断。已放行批次比对放行快照与当前有效封箱；未放行批次比对计划与当前进度。
 * discrepancies 为空表示无差异；quantityDifference 为 计划数量 - 当前有效封箱数量。
 */
public record LabelReconciliationResponse(
        String batchKey,
        String batchStatus,
        boolean released,
        boolean planRegistered,
        int plannedQuantity,
        int sealedQuantity,
        int quantityDifference,
        int activeCaseCount,
        int usedLabelCount,
        List<String> discrepancies
) {
}
