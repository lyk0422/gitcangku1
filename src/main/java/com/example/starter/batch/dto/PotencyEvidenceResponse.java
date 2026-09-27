package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 效价业务证据响应：固化计算时刻、模型版本、输入效价与舍入结果，只增不改。
 *
 * <p>数值字段为 null 时表示当时未登记效价模型（decision=NOT_CONFIGURED）。
 * occurredAt 为固化计算时刻；difference 为实际当前效价减要求阈值的差额（负值不合格）。
 */
public record PotencyEvidenceResponse(
        long id,
        String batchKey,
        String eventType,
        String commandType,
        String commandKey,
        Instant occurredAt,
        Integer modelVersion,
        BigDecimal initialPotency,
        BigDecimal minPotency,
        BigDecimal decayRate,
        BigDecimal elapsedDays,
        BigDecimal decayedPotency,
        BigDecimal currentPotency,
        BigDecimal thresholdPotency,
        BigDecimal difference,
        String decision,
        String inputsJson,
        Instant createdAt
) {
}
