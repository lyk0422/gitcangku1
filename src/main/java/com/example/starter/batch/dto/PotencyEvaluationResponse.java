package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 固化的效价计算记录：放行/拆分/合批时写入，含计算时刻、模型版本、
 * 输入效价参数与舍入结果；创建后不可改写。relatedKey 仅拆分/合批时有值（子批键）。
 */
public record PotencyEvaluationResponse(
        long id,
        String batchKey,
        String operation,
        String relatedKey,
        Instant evaluatedAt,
        int modelVersion,
        BigDecimal initialPotency,
        BigDecimal decayRatePerDay,
        BigDecimal minReleasePotency,
        long elapsedDays,
        BigDecimal currentPotency
) {
}
