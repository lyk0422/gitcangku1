package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 单条封箱记录响应。status 为 ACTIVE/VOIDED；voidedAt 与 voidReason 未作废时为 null。
 */
public record CaseResponse(
        String batchKey,
        String caseKey,
        long labelNo,
        int quantity,
        String status,
        int version,
        Instant createdAt,
        Instant voidedAt,
        String voidReason
) {
}
