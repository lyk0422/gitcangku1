package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 温度读数上传响应。segmentStatus 为本次读数落定后的运输段状态；
 * batchStatus 为批次对外呈现状态（温控冻结期间为 TEMPERATURE_HOLD）。
 */
public record ReadingResponse(
        String batchKey,
        String segmentKey,
        Instant recordedAt,
        BigDecimal temperature,
        boolean inRange,
        String segmentStatus,
        String batchStatus,
        Instant createdAt
) {
}
