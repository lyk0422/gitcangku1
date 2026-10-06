package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;
import com.example.starter.batch.BatchType;

import java.time.Instant;
import java.util.List;

/**
 * 批次概要响应。producedAt/createdAt 为 UTC instant。
 * quantity/maxSampleAmount/consumedAmount/remainingQuantity 仅 QUANTIFIED 批有值，
 * STANDARD 批为 null。
 */
public record BatchResponse(
        String batchKey,
        String productCode,
        String batchNo,
        Instant producedAt,
        BatchStatus status,
        List<String> requiredTests,
        Instant createdAt,
        BatchType batchType,
        Integer quantity,
        Integer maxSampleAmount,
        Integer consumedAmount,
        Integer remainingQuantity
) {
}
