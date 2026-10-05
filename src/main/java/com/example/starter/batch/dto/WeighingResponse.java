package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 称量/更正响应：返回新记录 id 与更新后的称量链版本。
 */
public record WeighingResponse(
        long weighingId,
        String batchKey,
        String ingredient,
        BigDecimal amount,
        String source,
        Long supersedesId,
        long weighingVersion,
        String operatorId,
        Instant createdAt
) {
}
