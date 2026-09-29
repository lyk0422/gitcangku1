package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 产率门禁放行结果：记录放行时裁决所用的核算版本与偏差状态。
 */
public record YieldReleaseResponse(
        String batchKey,
        int version,
        String requestId,
        BigDecimal deviation,
        BigDecimal toleranceLimit,
        String status,
        String actorId,
        Instant releasedAt
) {
}
