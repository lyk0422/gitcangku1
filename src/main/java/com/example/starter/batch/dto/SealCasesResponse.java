package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量封箱响应。totalSealedQuantity 为当前有效封箱数量之和（件）；
 * plannedQuantity 为计划数量；remainingQuantity 为差额（计划 - 已封）。
 */
public record SealCasesResponse(
        String batchKey,
        List<CaseResponse> cases,
        int totalSealedQuantity,
        int plannedQuantity,
        int remainingQuantity,
        Instant sealedAt
) {
}
