package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 运输段响应。status 为 NORMAL 或 EXCURSION；EXCURSION 为终态，段与读数历史不可改写。
 */
public record TransportSegmentResponse(
        String batchKey,
        String segmentKey,
        Instant startAt,
        Instant endAt,
        BigDecimal minTemp,
        BigDecimal maxTemp,
        String recordedBy,
        String status,
        Instant createdAt
) {
}
