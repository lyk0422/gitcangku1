package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次效价模型版本响应。版本只增不改，effectiveAt 为该版本计算基准 UTC 时刻，
 * 后续修订产生新版本，不改变历史版本与已固化结论。
 */
public record PotencyModelResponse(
        String batchKey,
        int version,
        BigDecimal initialPotency,
        BigDecimal minPotency,
        BigDecimal decayRate,
        String actorId,
        String commandKey,
        Instant effectiveAt,
        Instant createdAt
) {
}
