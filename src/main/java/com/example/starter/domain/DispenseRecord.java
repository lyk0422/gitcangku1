package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 领用流水：仅记录成功扣减；固化当时的剩余量快照与截止依据，失败请求不写入。
 */
public record DispenseRecord(
        long id,
        long bottleId,
        String requestId,
        BigDecimal amountMl,
        BigDecimal remainingAfterMl,
        DeadlineBasis deadlineBasis,
        Instant dispensedAt) {
}