package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 仓储段响应：携带当前生效限值与版本；历史偏差按各自生成时的版本判定，不随版本变化。
 */
public record SegmentResponse(
        String batchKey,
        String segmentKey,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
