package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 效价衰减模型明细。registeredAt 为模型登记时刻（UTC），同时是衰减起算点。
 */
public record PotencyModelResponse(
        String batchKey,
        int version,
        BigDecimal initialPotency,
        BigDecimal minReleasePotency,
        BigDecimal decayRatePerDay,
        Instant registeredAt,
        Instant createdAt
) {
}
