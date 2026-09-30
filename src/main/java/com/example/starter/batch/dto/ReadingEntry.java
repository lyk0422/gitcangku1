package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 时刻查询中的原始读数条目，按采集时刻升序。
 * outOfLimit 表示该读数相对其登记时段限值越界；outOfLimitDeviationKey 为对应偏差键（无则为 null）。
 */
public record ReadingEntry(
        String requestId,
        Instant readingTime,
        BigDecimal temperatureCelsius,
        boolean outOfLimit,
        String outOfLimitDeviationKey,
        Instant createdAt
) {
}
