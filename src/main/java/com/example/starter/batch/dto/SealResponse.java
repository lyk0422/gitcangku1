package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 封箱记录响应。voidedAt/voidReason 仅作废后有值，活跃记录为 null。
 */
public record SealResponse(
        String batchKey,
        String sealKey,
        long labelNo,
        int quantity,
        String status,
        int version,
        Instant createdAt,
        Instant voidedAt,
        String voidReason
) {
}
