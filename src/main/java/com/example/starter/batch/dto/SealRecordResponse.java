package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 单个封箱版本记录（append-only 证据）。status 为 ACTIVE/VOIDED；
 * 未作废时 voidedAt/voidReason/voidCommandKey 为 null。
 */
public record SealRecordResponse(
        long id,
        long labelNo,
        int quantity,
        int version,
        String status,
        String commandKey,
        Instant sealedAt,
        Instant voidedAt,
        String voidReason,
        String voidCommandKey
) {
}
