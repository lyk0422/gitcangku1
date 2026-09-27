package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次当前效价诊断查询响应。批次未登记效价模型时 model 为 null，
 * 且 elapsedDays/currentPotency/requiredPotency/difference/releaseQualified 均为 null。
 * difference = currentPotency - minReleasePotency（负值表示低于放行阈值）。
 */
public record PotencyStatusResponse(
        String batchKey,
        PotencyModelResponse model,
        Instant evaluatedAt,
        Long elapsedDays,
        BigDecimal currentPotency,
        BigDecimal requiredPotency,
        BigDecimal difference,
        Boolean releaseQualified
) {
}
