package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 称量写入响应。correctsWeighingId 非空表示本记录为更正；
 * weighingVersion 为写入落定后的称量链版本。createdAt 为 UTC instant。
 */
public record WeighingResponse(
        long weighingId,
        String batchKey,
        String ingredient,
        BigDecimal mass,
        Long correctsWeighingId,
        long weighingVersion,
        Instant createdAt
) {
}