package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 样本消耗凭证明细：破坏性检验消耗的实物样本登记。
 * quantity 为本次消耗数量（正整数件）；remainingAfter 为该次消耗落定后的批次剩余量；
 * createdAt 为 UTC instant。凭证创建后不可撤销。
 */
public record SampleConsumptionResponse(
        String batchKey,
        String sampleKey,
        String testKey,
        int quantity,
        int remainingAfter,
        Instant createdAt
) {
}
