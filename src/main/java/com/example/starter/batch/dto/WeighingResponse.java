package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 称量/更正响应：携带新记录、提交后的称量链版本，以及全部成分的有效累计量（克）。
 */
public record WeighingResponse(
        String batchKey,
        long weighingId,
        String ingredient,
        BigDecimal massGrams,
        String kind,
        Long replacesWeighingId,
        int chainVersion,
        Map<String, BigDecimal> totals,
        Instant createdAt
) {
}