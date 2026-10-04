package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 清洗记录响应。residual 为未舍入的十进制原文字符串（毫克）；
 * confirmedBy/confirmedAt 在未确认为 null。
 */
public record CleaningResponse(
        long cleaningId,
        String prevBatchKey,
        String nextBatchKey,
        String residual,
        String status,
        int version,
        String submittedBy,
        String confirmedBy,
        Instant createdAt,
        Instant confirmedAt
) {
}