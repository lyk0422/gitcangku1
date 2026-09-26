package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 封箱明细。voidReason/voidedAt 仅已作废封箱非空；历史字段创建后不改写。
 */
public record CartonResponse(
        String cartonKey,
        long labelNo,
        int quantity,
        String status,
        int version,
        String voidReason,
        Instant voidedAt,
        Instant createdAt
) {
}
