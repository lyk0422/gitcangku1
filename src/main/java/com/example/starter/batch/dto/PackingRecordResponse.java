package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 单条封箱版本明细（只增不改）。status=ACTIVE 时 voidedBy/voidReason/voidedAt 恒为 null；
 * status=VOID 时三者非空，封箱数量与标签已释放。时间均为 UTC instant。
 */
public record PackingRecordResponse(
        long packingId,
        long labelNo,
        int quantity,
        String packedBy,
        String status,
        Instant packedAt,
        String voidedBy,
        String voidReason,
        Instant voidedAt
) {
}
