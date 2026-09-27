package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 封箱作废响应。releasedQuantity 为本次作废释放的封箱数量（件）；
 * activeSealedQuantity 为作废后剩余有效封箱数量；remainingQuantity 为与计划的差额。
 */
public record VoidCaseResponse(
        String batchKey,
        String caseKey,
        long labelNo,
        int releasedQuantity,
        String status,
        int version,
        String reason,
        Instant voidedAt,
        int activeSealedQuantity,
        int plannedQuantity,
        int remainingQuantity
) {
}
