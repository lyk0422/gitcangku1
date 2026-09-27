package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 批次效价诊断视图：当前生效模型版本 + 按可注入时钟计算的此刻效价与要求值差额，
 * 以及该批次全部固化的历史效价证据（按固化顺序稳定排序）。
 *
 * <p>未登记模型的批次 model/currentPotency 等数值字段为 null，decision 为 NOT_CONFIGURED。
 */
public record PotencyViewResponse(
        String batchKey,
        PotencyModelResponse model,
        BigDecimal elapsedDays,
        BigDecimal decayedPotency,
        BigDecimal currentPotency,
        BigDecimal thresholdPotency,
        BigDecimal difference,
        String decision,
        Instant evaluatedAt,
        List<PotencyEvidenceResponse> evidence
) {
}
