package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 样本消耗凭证响应。amount 为本次消耗数量（件）；remainingAfter 为该次消耗落账后的
 * 批次剩余量（件）；createdAt 为 UTC instant。
 */
public record SampleVoucherResponse(
        String batchKey,
        String sampleKey,
        String testKey,
        int amount,
        int remainingAfter,
        Instant createdAt
) {
}
