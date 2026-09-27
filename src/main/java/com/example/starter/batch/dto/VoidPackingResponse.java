package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 封箱作废响应：作废后该版本 status 恒为 VOID，并返回释放后的批次累计值。
 * releasedQuantity 为释放数量（等于该版本原封箱数量，单位：件），
 * releasedLabel 为释放的标签号；packedQuantity/usedLabelCount 为释放后的实际值。
 */
public record VoidPackingResponse(
        String batchKey,
        long packingId,
        long releasedLabel,
        int releasedQuantity,
        String voidedBy,
        String reason,
        Instant voidedAt,
        int packedQuantity,
        int usedLabelCount
) {
}
