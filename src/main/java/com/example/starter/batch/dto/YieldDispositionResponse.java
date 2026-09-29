package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单次处置确认信息。seq 为确认序号（1/2），createdAt 为 UTC 确认时间。
 */
public record YieldDispositionResponse(
        String handlerId,
        int seq,
        Instant createdAt
) {
}
