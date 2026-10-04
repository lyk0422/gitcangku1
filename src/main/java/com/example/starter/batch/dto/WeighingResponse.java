package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 称量/更正响应。weighingId 为新称量行ID；replacesWeighingId 仅更正时非空，
 * 指向被替代的称量ID；weighingVersion 为写入后的称量链版本。
 */
public record WeighingResponse(
        long weighingId,
        String batchKey,
        String ingredient,
        BigDecimal amountGrams,
        Long replacesWeighingId,
        int weighingVersion,
        Instant createdAt
) {
}