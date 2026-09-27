package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 批次概要响应。producedAt/createdAt 为 UTC instant。
 * 效价字段（initialPotency/minReleasePotency/decayRatePerDay/quantity/potencyEpochAt）
 * 在未登记效价的批次上为 null（null 语义稳定，字段始终出现）。
 */
public record BatchResponse(
        String batchKey,
        String productCode,
        String batchNo,
        Instant producedAt,
        BatchStatus status,
        List<String> requiredTests,
        Instant createdAt,
        BigDecimal initialPotency,
        BigDecimal minReleasePotency,
        BigDecimal decayRatePerDay,
        BigDecimal quantity,
        Instant potencyEpochAt
) {
}
