package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 称量历史记录条目：含已失效（被更正替代）记录；effective 标识当前是否有效。
 */
public record WeighingRecordResponse(
        long weighingId,
        String batchKey,
        String ingredient,
        BigDecimal amount,
        String source,
        Long supersedesId,
        Long supersededBy,
        boolean effective,
        String operatorId,
        Instant createdAt
) {
}
