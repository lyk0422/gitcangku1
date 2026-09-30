package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 时刻查询中的原始读数条目：展示读数温度与当时生效的限值快照。
 */
public record ReadingEntry(
        String requestId,
        Instant readAt,
        BigDecimal temperature,
        BigDecimal lowerLimit,
        BigDecimal upperLimit
) {
}
