package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 仓储段概要响应。限值单位为摄氏度(℃)，createdAt 为 UTC instant。
 */
public record SegmentResponse(
        String batchKey,
        String segmentKey,
        BigDecimal upperLimit,
        BigDecimal lowerLimit,
        Instant createdAt
) {
}
